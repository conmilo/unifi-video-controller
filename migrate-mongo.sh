#!/bin/bash
# migrate-mongo.sh -- one-shot 4.0 -> 4.2 -> 4.4 fCV stepper.
#
# Background:
#   UniFi Video 3.10.13 historically bundled MongoDB 3.x/4.0 and stored its
#   featureCompatibilityVersion at "4.0". This image runs MongoDB 4.4. The
#   MongoDB upgrade rules require that a 4.4 binary will only open a dataset
#   whose fCV is >= 4.2; you cannot skip the intermediate. So we ship the
#   4.2 binary purely to bridge that one step.
#
# Behaviour:
#   - No data dir / empty data dir -> nothing to do, exit 0.
#   - fCV already 4.4 -> nothing to do, exit 0.
#   - fCV == 4.2 -> start 4.4, set fCV to 4.4, shut down, exit 0.
#   - fCV == 4.0 -> start 4.2, set fCV to 4.2, shut down; then start 4.4,
#                   set fCV to 4.4, shut down, exit 0.
#
#   Fully idempotent: re-running after success is a no-op.
#
# Logs go to /var/log/unifi-video/migrate-mongod.log.

set -euo pipefail

# UniFi Video 3.10.13's bundled mongod uses the WiredTiger storage engine
# with dbPath /usr/lib/unifi-video/data/db-wt (which is a symlink to
# /var/lib/unifi-video/db-wt). The legacy /var/lib/unifi-video/db directory
# is a leftover from the long-defunct MMAPv1 engine days and must NOT be
# used by migrate-mongo: pointing mongod at an empty db/ dir silently
# initialises a fresh WiredTiger database with default fCV (=binary
# version), which looks identical to "already migrated" but leaves the
# user's real data at db-wt untouched and unmigrated.
DB_PATH="${DB_PATH:-/var/lib/unifi-video/db-wt}"
MONGO_PORT="${MONGO_PORT:-7441}"
LOG_DIR="/var/log/unifi-video"
MIGRATION_LOG="${LOG_DIR}/migrate-mongod.log"
MONGO_CLIENT="/opt/mongodb-4.4/bin/mongo"
MONGOD_44="/opt/mongodb-4.4/bin/mongod"
MONGOD_42="/opt/mongodb-4.2/bin/mongod"

mkdir -p "${LOG_DIR}"

log() {
    echo "[migrate-mongo] $*" | ts '%Y-%m-%d %H:%M:%.S' | tee -a "${MIGRATION_LOG}"
}

# Defense-in-depth ownership reclaim. Runs on EVERY exit (success,
# failure, early-return), so even if a future code path forgets to
# chown or some external tooling slips files into DB_PATH with a
# foreign UID mid-migration, the runtime mongod can still open the
# database. Quiet-failing so we don't fight DSM ACLs or fully-correct
# states.
finalize_ownership() {
    if [[ -n "${PUID:-}" ]] && [[ -n "${PGID:-}" ]] && [[ -d "${DB_PATH}" ]]; then
        chown -R "${PUID}:${PGID}" "${DB_PATH}" 2>/dev/null || true
    fi
}
trap finalize_ownership EXIT

# Fresh install -> nothing to migrate.
if [[ ! -d "${DB_PATH}" ]] || [[ -z "$(ls -A "${DB_PATH}" 2>/dev/null || true)" ]]; then
    log "No existing MongoDB data dir at ${DB_PATH}, skipping fCV migration."
    exit 0
fi

# Sanity: a real WiredTiger DB always contains a WiredTiger.turtle metadata
# file. If DB_PATH has files but no .turtle, we are looking at an empty
# legacy MMAPv1 dir, a partial install, or simply the wrong path -- bail
# loudly rather than have mongod silently initialise a fresh DB on top.
if [[ ! -f "${DB_PATH}/WiredTiger.turtle" ]]; then
    log "ERROR: ${DB_PATH} contains files but no WiredTiger.turtle metadata."
    log "       This is not a WiredTiger database directory. Refusing to"
    log "       initialise a fresh DB here. Check that DB_PATH (currently"
    log "       '${DB_PATH}') points at UniFi Video's real data dir."
    exit 1
fi

# Start mongod (specified binary) against DB_PATH, wait until it accepts
# connections on MONGO_PORT, or fail.
#
# IMPORTANT: the probe mongod is started as the unifi-video user (via
# runuser), NOT as root. Earlier releases ran it as root (inherited from
# run.sh PID 1), which meant any WiredTiger journal/checkpoint files
# the probe touched -- even on the happy-path "fCV is already 4.4"
# branch -- ended up owned by root, and the subsequent runtime mongod
# (which runs as unifi-video) failed with "Permission denied:
# WiredTiger.turtle". Starting the probe as unifi-video from the
# outset means there is no ownership skew to repair afterwards.
start_mongo() {
    local binary="$1"
    local label="$2"
    log "Starting ${label} from ${binary} against ${DB_PATH} (as unifi-video user)..."

    # mongod --fork sometimes exits non-zero on the very first attempt
    # against a freshly-mounted prod-data snapshot, with NO output to
    # MIGRATION_LOG -- the parent process appears to observe a transient
    # filesystem-coherency error on a file the chown / lock-truncation
    # pass in run.sh just touched.  We retry with progressive backoff
    # before treating it as a real failure.  The mongod stderr is
    # captured to a dedicated buffer file (not /dev/null) so the
    # diagnostic message in the failure path is useful.
    local attempt mongod_rc mongod_stderr
    mongod_stderr=$(mktemp /tmp/mongod-stderr.XXXXXX)
    for attempt in 1 2 3 4; do
        mongod_rc=0
        runuser -u unifi-video -- "${binary}" \
                --dbpath "${DB_PATH}" \
                --port "${MONGO_PORT}" \
                --bind_ip 127.0.0.1 \
                --logpath "${MIGRATION_LOG}" \
                --logappend \
                --fork \
                --quiet >"${mongod_stderr}" 2>&1 || mongod_rc=$?
        if [[ "${mongod_rc}" == "0" ]]; then
            break
        fi
        if [[ "${attempt}" == "4" ]]; then
            log "ERROR: ${label} failed to fork after 4 attempts (last exit ${mongod_rc})."
            log "       Last 5 lines of mongod stderr:"
            tail -5 "${mongod_stderr}" 2>/dev/null | sed 's/^/         /' || true
            log "       See ${MIGRATION_LOG} for the mongod logpath output (may be empty)."
            rm -f "${mongod_stderr}"
            return 1
        fi
        local backoff=$(( attempt * 2 ))
        log "WARN: ${label} fork-pass attempt ${attempt} exited ${mongod_rc}; retrying in ${backoff}s (transient cold-mount race)."
        # Defensive: re-truncate the lock in case the failed attempt
        # left it non-empty again.  Also sync the data dir.
        : > "${DB_PATH}/mongod.lock" 2>/dev/null || true
        sync -f "${DB_PATH}" 2>/dev/null || sync 2>/dev/null || true
        sleep "${backoff}"
    done
    rm -f "${mongod_stderr}"
    # Poll until mongod accepts connections (cap at 120s for warm-DB reopen).
    for _ in $(seq 1 120); do
        if "${MONGO_CLIENT}" --quiet --port "${MONGO_PORT}" \
                --eval 'db.runCommand({ ping: 1 })' admin >/dev/null 2>&1; then
            log "${label} accepting connections on port ${MONGO_PORT}."
            return 0
        fi
        sleep 1
    done
    log "ERROR: ${label} did not accept connections within 120s."
    return 1
}

stop_mongo() {
    local label="$1"
    log "Shutting down ${label}..."
    "${MONGO_CLIENT}" --quiet --port "${MONGO_PORT}" \
        --eval 'db.adminCommand({ shutdown: 1 })' admin >/dev/null 2>&1 || true
    # Wait for the listening port to actually close.
    for _ in $(seq 1 60); do
        if ! "${MONGO_CLIENT}" --quiet --port "${MONGO_PORT}" \
                --eval 'db.runCommand({ ping: 1 })' admin >/dev/null 2>&1; then
            log "${label} stopped."
            return 0
        fi
        sleep 1
    done
    log "WARN: ${label} did not stop within 60s."
    return 1
}

get_fcv() {
    "${MONGO_CLIENT}" --quiet --port "${MONGO_PORT}" \
        --eval 'JSON.stringify(db.adminCommand({ getParameter: 1, featureCompatibilityVersion: 1 }))' \
        admin 2>/dev/null \
        | jq -r '.featureCompatibilityVersion.version // empty'
}

set_fcv() {
    local target="$1"
    log "Setting featureCompatibilityVersion to ${target}..."
    "${MONGO_CLIENT}" --quiet --port "${MONGO_PORT}" \
        --eval "db.adminCommand({ setFeatureCompatibilityVersion: \"${target}\" })" \
        admin >/dev/null
}

# ---------------------------------------------------------------------------
# Step 1: probe with 4.4. If fCV is already 4.4 we exit; if 4.2 we step once;
# if older, mongod 4.4 refuses to start and we fall through to the 4.2 stepper.
# ---------------------------------------------------------------------------
if start_mongo "${MONGOD_44}" "mongod 4.4 (probe)"; then
    current_fcv="$(get_fcv || true)"
    log "mongod 4.4 reports current fCV='${current_fcv:-unknown}'."

    case "${current_fcv}" in
        "4.4")
            log "fCV is already 4.4; nothing to do."
            stop_mongo "mongod 4.4 (probe)"
            exit 0
            ;;
        "4.2")
            log "Stepping fCV 4.2 -> 4.4."
            set_fcv "4.4"
            stop_mongo "mongod 4.4 (probe)"
            log "Migration complete: fCV is now 4.4."
            exit 0
            ;;
        *)
            log "fCV='${current_fcv}' is too old for 4.4 to bump directly; falling back to 4.2 stepper."
            stop_mongo "mongod 4.4 (probe)" || true
            ;;
    esac
fi

# ---------------------------------------------------------------------------
# Step 2: open with 4.2 binary, bump fCV to 4.2 if needed.
# ---------------------------------------------------------------------------
if ! start_mongo "${MONGOD_42}" "mongod 4.2 (bridge)"; then
    log "ERROR: mongod 4.2 failed to start. Cannot migrate."
    exit 1
fi

current_fcv="$(get_fcv || true)"
log "mongod 4.2 reports current fCV='${current_fcv:-unknown}'."

if [[ "${current_fcv}" != "4.2" ]]; then
    set_fcv "4.2"
fi
stop_mongo "mongod 4.2 (bridge)"

# ---------------------------------------------------------------------------
# Step 3: open with 4.4 binary, bump fCV to 4.4, done.
# ---------------------------------------------------------------------------
if ! start_mongo "${MONGOD_44}" "mongod 4.4 (finalize)"; then
    log "ERROR: mongod 4.4 failed to start after 4.2 step. Migration aborted; the dataset is now at fCV 4.2 -- safe but not yet finalized."
    exit 1
fi

set_fcv "4.4"
stop_mongo "mongod 4.4 (finalize)"

# Ownership cleanup is handled by the finalize_ownership EXIT trap
# registered at the top of this script. No explicit chown needed here.

log "Migration complete: fCV is now 4.4."
exit 0
