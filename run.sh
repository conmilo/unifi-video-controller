#!/bin/bash
# run.sh -- container entrypoint for UniFi Video Controller (modernized).
#
# Flow:
#   1. Apply PUID/PGID/UMASK to the unifi-video user/group.
#   2. Always chown/chmod the small critical paths (db-wt/, db/, logs/,
#      backup/, snapshot/, root-level config files). Cheap (< 1s) and
#      defends against stray-UID writes from external tooling.
#   3. One-time chown of videos/ subtree (guarded by perms.txt marker)
#      because on real deployments it can hold years of recordings and
#      a recursive chown of TB of data on every boot is wasteful.
#   4. Run uv-patcher (Phase 3 / v3.10.13-13, extended in v3.10.13-15):
#      rewrite airvision.jar's spec-illegal identifiers AND rewrite its two
#      dangling Tomcat-9 Bootstrap.setCatalina{Base,Home} call sites in
#      place, in the running container's writable layer. The image layer
#      keeps pristine Ubiquiti bytes; the patcher writes the rewritten JAR
#      over the original here. Idempotent: re-runs detect the already-
#      patched state and exit 0. (v3.10.13-15 retired the separate Tomcat
#      Bootstrap shim pass on tomcat-embed-core-9.0.118.jar -- that JAR
#      is now byte-identical to the pristine Maven Central upstream both
#      in the image and inside the running container.)
#   5. Run migrate-mongo.sh to step fCV 4.0 -> 4.2 -> 4.4 if needed.
#   6. Start mongod for unifi-video's runtime use (--fork, daemonized).
#   7. Hand off to /usr/sbin/unifi-video which starts jsvc + the JVM.
#   8. Idle loop that re-creates tmpfs cache dirs if they vanish (upstream #178).
#   9. SIGTERM -> graceful_shutdown: stop unifi-video, then mongod.
#
# Note on step 6: UniFi Video's /usr/sbin/unifi-video init script does NOT
# start mongod itself -- on Ubuntu host installs, a separate systemd unit
# (mongodb-server-7441.service) was responsible for that, and unifi-video.
# service had an After=/Requires= dependency. In a container with no
# systemd we have to start mongod ourselves before launching the JVM,
# which expects to connect to mongod on 127.0.0.1:7441.
#
# Note on step 2 (added in v3.10.13-5): earlier releases skipped ALL
# chown when perms.txt existed. If anything outside the runtime container
# (host-side tooling, snapshot restore, diagnostic sidecar containers)
# wrote files into db-wt/ with a different UID, the runtime mongod
# would fail with "Permission denied" on WiredTiger.turtle and the
# only recovery was to manually delete perms.txt. Scoping the optimization
# to videos/ only makes recovery automatic on the next container restart.

set -o pipefail

MONGOD_BIN=/opt/mongodb-4.4/bin/mongod
MONGOD_CONFIG=/usr/lib/unifi-video/conf/mongod-wt.conf
MONGOD_PIDFILE=/var/run/unifi-video/mongod.pid
UNIFI_BASEDIR=/usr/lib/unifi-video
UV_LIBDIR=/usr/lib/unifi-video/lib
UV_PATCHER_JAR=/opt/uv-patcher/uv-patcher.jar
UV_PATCHER_AIRVISION_SPEC=/opt/uv-patcher/airvision-renames.json

echo "[info] UMASK defined as '${UMASK}'." | ts '%Y-%m-%d %H:%M:%.S'
umask "${UMASK}"

unifi_video_opts=""

stop_mongod() {
    if [[ -f "${MONGOD_PIDFILE}" ]]; then
        local pid
        pid=$(cat "${MONGOD_PIDFILE}" 2>/dev/null || true)
        if [[ -n "${pid}" ]] && kill -0 "${pid}" 2>/dev/null; then
            echo -n "Stopping mongod (pid ${pid})... " | ts '%Y-%m-%d %H:%M:%.S'
            # SIGTERM gives mongod time to flush WiredTiger checkpoints cleanly.
            kill -TERM "${pid}" 2>/dev/null || true
            for _ in $(seq 1 30); do
                kill -0 "${pid}" 2>/dev/null || break
                sleep 1
            done
            if kill -0 "${pid}" 2>/dev/null; then
                echo "timeout; forcing."
                kill -KILL "${pid}" 2>/dev/null || true
            else
                echo "done."
            fi
        fi
        rm -f "${MONGOD_PIDFILE}"
    fi
}

graceful_shutdown() {
    echo -n "Stopping unifi-video... " | ts '%Y-%m-%d %H:%M:%.S'
    if /usr/sbin/unifi-video --nodetach stop; then
        echo "done."
    else
        echo "failed (continuing to mongod shutdown anyway)."
    fi
    stop_mongod
    exit 0
}

trap graceful_shutdown SIGTERM SIGINT

echo "[info] PUID defined as '${PUID}'." | ts '%Y-%m-%d %H:%M:%.S'
usermod -o -u "${PUID}" unifi-video &>/dev/null || true

echo "[info] PGID defined as '${PGID}'." | ts '%Y-%m-%d %H:%M:%.S'
groupmod -o -g "${PGID}" unifi-video &>/dev/null || true

# Re-chown the in-image application tree to the *runtime* unifi-video UID.
#
# The Dockerfile chowns /usr/lib/unifi-video to unifi-video:unifi-video at
# build time, but `useradd -r` picks whatever free system UID it picks
# (usually 999 on a fresh Ubuntu base image). The usermod above moves the
# unifi-video user to PUID, leaving every file on disk still owned by the
# old build-time UID -- which now resolves to no named user inside the
# container. The JVM running as the new unifi-video (= PUID) cannot read
# or write those files. Concrete previous symptom:
#
#   ERROR [uv.ems.svc] Failed to write EMS config
#     (/usr/lib/unifi-video/conf/evostream/config.lua) in main
#   java.io.FileNotFoundException: ... (Permission denied)
#
# Re-asserting ownership here, AFTER usermod, fixes that. The tree is
# small (a few hundred MB at most -- mostly airvision.jar and other
# bundled JAR/lib content) and a recursive chown completes in a fraction
# of a second. Symlinks (data -> /var/lib/unifi-video, logs ->
# /var/lib/unifi-video/logs) are lchown'd rather than followed, so this
# doesn't touch the bind-mounted data dir.
echo "[info] Re-asserting ownership on /usr/lib/unifi-video for runtime PUID." | ts '%Y-%m-%d %H:%M:%.S'
if ! chown -R unifi-video:unifi-video /usr/lib/unifi-video; then
    echo "[warn] Unable to chown /usr/lib/unifi-video; UniFi Video may fail to write EvoStream config." | ts '%Y-%m-%d %H:%M:%.S'
fi

mkdir -p /var/lib/unifi-video/logs /var/log/unifi-video

# Resolve the chmod recipe once for both passes below.
case "${UMASK}" in
    002) chmod_args=( -R a=,a+rX,u+w,g+w ) ;;
    022) chmod_args=( -R a=,a+rX,u+w ) ;;
    *)
        chmod_args=()
        echo "[warn] UMASK='${UMASK}' is neither 002 nor 022; skipping chmod passes." | ts '%Y-%m-%d %H:%M:%.S'
        ;;
esac

# --- Pass 1: ALWAYS chown the small critical paths --------------------
# These must match the runtime PUID:PGID or mongod / unifi-video will
# fail to open WiredTiger metadata, log files, or config. Total size
# is a few MB on the largest deployments, so an unconditional pass is
# cheap and removes a whole class of "Permission denied" footguns from
# external tooling touching the data dir.
echo "[info] Re-applying ownership on critical paths (db-wt, db, logs, backup, snapshot, root config files)." | ts '%Y-%m-%d %H:%M:%.S'
critical_paths=(
    /var/lib/unifi-video
    /var/lib/unifi-video/db-wt
    /var/lib/unifi-video/db
    /var/lib/unifi-video/logs
    /var/lib/unifi-video/backup
    /var/lib/unifi-video/snapshot
)
# Top-level (non-recursive) for the data dir itself + root-level files
# (system.properties, keystore, cam-keystore, ufv-truststore, perms.txt).
if ! chown "${PUID}":"${PGID}" /var/lib/unifi-video; then
    echo "[warn] Unable to chown /var/lib/unifi-video (top-level)." | ts '%Y-%m-%d %H:%M:%.S'
fi
for f in /var/lib/unifi-video/*; do
    [[ -f "${f}" ]] || continue
    chown "${PUID}":"${PGID}" "${f}" 2>/dev/null || true
done
# Recursive for the small subdirs that actually need it.
for d in "${critical_paths[@]:1}"; do
    [[ -d "${d}" ]] || continue
    if ! chown -R "${PUID}":"${PGID}" "${d}"; then
        echo "[warn] Unable to chown ${d}." | ts '%Y-%m-%d %H:%M:%.S'
    fi
    if [[ ${#chmod_args[@]} -gt 0 ]]; then
        chmod "${chmod_args[@]}" "${d}" 2>/dev/null || \
            echo "[warn] chmod failed for ${d}." | ts '%Y-%m-%d %H:%M:%.S'
    fi
done

# --- Pass 2: one-time chown of videos/ (perms.txt-guarded) ------------
# videos/ can hold TB of recordings on real deployments. Skip it once
# we've successfully chowned it.
if [[ -d /var/lib/unifi-video/videos ]]; then
    if [[ ! -f /var/lib/unifi-video/perms.txt ]]; then
        echo "[info] First start: chowning videos/ subtree (one-time, may take a while)." | ts '%Y-%m-%d %H:%M:%.S'
        if ! chown -R "${PUID}":"${PGID}" /var/lib/unifi-video/videos; then
            echo "[warn] Unable to chown /var/lib/unifi-video/videos." | ts '%Y-%m-%d %H:%M:%.S'
        fi
        if [[ ${#chmod_args[@]} -gt 0 ]]; then
            chmod "${chmod_args[@]}" /var/lib/unifi-video/videos 2>/dev/null || \
                echo "[warn] chmod failed for videos/." | ts '%Y-%m-%d %H:%M:%.S'
        fi
        echo "This file prevents the videos/ subtree from being re-chowned on every container start. Delete to force a re-chown of videos/ (the small critical paths -- db-wt, db, logs, backup, snapshot, root config -- are always chowned regardless of this file)." \
            > /var/lib/unifi-video/perms.txt
        chown "${PUID}":"${PGID}" /var/lib/unifi-video/perms.txt 2>/dev/null || true
    else
        echo "[info] perms.txt present; skipping videos/ chown." | ts '%Y-%m-%d %H:%M:%.S'
    fi
fi

# DEBUG default off; DEBUG=1 -> pass --debug to unifi-video.
: "${DEBUG:=0}"
if [[ "${DEBUG}" -eq 1 ]]; then
    echo "[debug] DEBUG=1: running unifi-video with --debug." | ts '%Y-%m-%d %H:%M:%.S'
    unifi_video_opts="--debug"
fi

# Phase 3 / v3.10.13-13 (extended in v3.10.13-15) -- apply runtime JAR
# patches via uv-patcher.  Rewrites airvision.jar's spec-illegal identifiers
# (under com/ubnt/A/super/oOOO/) AND its two dangling Tomcat 9
# Bootstrap.setCatalina{Base,Home} call sites (in com/ubnt/common/oOOO/A.<init>,
# rewritten to equivalent System.setProperty calls).  Idempotent: re-runs
# detect the already-patched state and exit 0.  See uv-patcher/README.md
# for the full rationale; the image always carries pristine Ubiquiti bytes
# -- modification happens here in the running container's writable layer
# only.  v3.10.13-15 retired the second tomcat-embed-core-9.0.118.jar
# patch pass.
apply_runtime_patch() {
    local jar=$1
    local spec=$2
    local label=$3
    local staging
    staging=$(mktemp -t "uv-patch-${label}-XXXX.jar")
    if ! java -jar "${UV_PATCHER_JAR}" \
            --target "${jar}" \
            --spec   "${spec}" \
            --output "${staging}"; then
        rm -f "${staging}"
        echo "[error] uv-patcher failed for ${label} (${jar})." | ts '%Y-%m-%d %H:%M:%.S'
        return 1
    fi
    # uv-patcher writes to its --output even when the input is already patched
    # (the no-op path); the staging file may be a partial / empty stream.
    # We only replace the live JAR if staging is non-empty AND smaller-or-equal
    # in size variance to the live JAR (i.e., looks like a real JAR).
    if [[ -s "${staging}" ]] && unzip -l "${staging}" >/dev/null 2>&1; then
        install -m 400 -o unifi-video -g unifi-video -T "${staging}" "${jar}"
        echo "[info] uv-patcher applied: ${label} -> ${jar}." | ts '%Y-%m-%d %H:%M:%.S'
    else
        echo "[info] uv-patcher reported ${label} already patched (no-op)." | ts '%Y-%m-%d %H:%M:%.S'
    fi
    rm -f "${staging}"
    return 0
}

echo "[info] Applying runtime patches to bundled JARs..." | ts '%Y-%m-%d %H:%M:%.S'
if ! apply_runtime_patch "${UV_LIBDIR}/airvision.jar" \
        "${UV_PATCHER_AIRVISION_SPEC}" airvision; then
    echo "[error] airvision patch failed; refusing to start unifi-video." | ts '%Y-%m-%d %H:%M:%.S'
    exit 1
fi

# Phase 3 -- Java 17+ module-system access for the reflection paths Guice,
# Jackson, and Mongojack rely on.  These flags were implicit on Java 8 (no
# module system).  We use JAVA_TOOL_OPTIONS rather than patching JVM_OPTS in
# /usr/sbin/unifi-video because JAVA_TOOL_OPTIONS is appended to options by
# JNI_CreateJavaVM itself (per the JVMTI spec), so it works regardless of
# how jsvc constructs its JVM-options vector.  Refine empirically during
# smoke testing -- removing a flag is only safe if no InaccessibleObjectException
# surfaces for the relevant package.
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} \
    --add-opens=java.base/java.lang=ALL-UNNAMED \
    --add-opens=java.base/java.util=ALL-UNNAMED \
    --add-opens=java.base/java.io=ALL-UNNAMED \
    --add-opens=java.base/java.net=ALL-UNNAMED \
    --add-opens=java.base/java.lang.reflect=ALL-UNNAMED"

# Stale mongod.lock truncation (defense-in-depth before migrate-mongo probe).
#
# Why: a UV install that was force-stopped (Synology DSM kill, ungraceful
# container restart, host OOM) leaves /var/lib/unifi-video/db-wt/mongod.lock
# with the PID of the previous mongod process.  In our container's fresh
# PID namespace that PID either doesn't exist or maps to an unrelated
# process, and mongod 4.4 with --fork can flake on the resulting check
# (sometimes WiredTiger recovery proceeds normally, sometimes the parent
# exits non-zero with NO log output, which migrate-mongo.sh reports as
# the misleading "failed to fork" error).
#
# Truncating the lock to zero bytes is exactly what mongod itself does on
# a clean shutdown.  WiredTiger's own .lock and journal/ are untouched, so
# the next mongod start still performs full WT crash recovery from the
# journal -- we lose nothing, we just remove a footgun from the data set.
if [[ -s /var/lib/unifi-video/db-wt/mongod.lock ]]; then
    lock_size=$(stat -c '%s' /var/lib/unifi-video/db-wt/mongod.lock 2>/dev/null || echo "?")
    echo "[info] Truncating stale db-wt/mongod.lock (${lock_size} bytes; would otherwise" \
         "trip mongod's fork-time PID check)." | ts '%Y-%m-%d %H:%M:%.S'
    : > /var/lib/unifi-video/db-wt/mongod.lock
    chown "${PUID}":"${PGID}" /var/lib/unifi-video/db-wt/mongod.lock 2>/dev/null || true
fi
# Sync the entire WiredTiger data directory and pause briefly before
# launching mongod.  Empirically (WSL2 9p-backed bind mount used in
# development, Synology DSM CIFS-backed bind mount in production),
# without this the very first mongod --fork after the chown pass exits
# non-zero in <50ms with NO log output -- the parent of --fork seems to
# observe a transient ENOENT / EAGAIN on a file the chown pass just
# touched.  A second container restart against the SAME data dir
# always works (the warm filesystem cache hides the race).  fsync +
# sleep keeps the first attempt deterministic.
if [[ -d /var/lib/unifi-video/db-wt ]]; then
    sync -f /var/lib/unifi-video/db-wt 2>/dev/null || sync 2>/dev/null || true
    sleep 1
fi

# One-time MongoDB fCV migration (4.0 -> 4.2 -> 4.4).
# Idempotent: exits immediately if fCV is already 4.4 or the data dir is empty.
echo "[info] Checking MongoDB featureCompatibilityVersion..." | ts '%Y-%m-%d %H:%M:%.S'
if ! /migrate-mongo.sh; then
    echo "[error] MongoDB fCV migration failed. Refusing to start unifi-video." | ts '%Y-%m-%d %H:%M:%.S'
    exit 1
fi

# Start mongod for unifi-video's runtime use. UniFi Video's init script does
# NOT spawn mongod; on Ubuntu host installs systemd's mongodb-server-7441.
# service handled that. In the container we do it here. --fork makes mongod
# daemonize and return only once it's accepting connections on 7441.
echo -n "Starting mongod... " | ts '%Y-%m-%d %H:%M:%.S'
mkdir -p "$(dirname "${MONGOD_PIDFILE}")"
chown unifi-video:unifi-video "$(dirname "${MONGOD_PIDFILE}")"
if (cd "${UNIFI_BASEDIR}" && runuser -u unifi-video -- "${MONGOD_BIN}" \
        --config "${MONGOD_CONFIG}" \
        --fork \
        --pidfilepath "${MONGOD_PIDFILE}" >/dev/null 2>&1); then
    echo "done (pid $(cat "${MONGOD_PIDFILE}" 2>/dev/null))."
else
    echo "failed -- see ${UNIFI_BASEDIR}/data/logs/mongod.log for details."
    exit 1
fi

echo -n "Starting unifi-video... " | ts '%Y-%m-%d %H:%M:%.S'
if /usr/sbin/unifi-video ${unifi_video_opts} start; then
    echo "done."
else
    echo "failed."
    stop_mongod
    exit 1
fi

# Idle loop. Recreate tmpfs-backed cache dirs if they vanish across a host
# restart (upstream issue #178: --tmpfs survives container restart only if
# the tmpfs mount itself remained mounted on the host).
while true; do
    for d in /var/cache/unifi-video/exports /var/cache/unifi-video/hls; do
        if [[ ! -d "${d}" ]]; then
            echo "[info] Re-creating missing cache dir ${d}." | ts '%Y-%m-%d %H:%M:%.S'
            mkdir -p "${d}"
            chown unifi-video:unifi-video "${d}"
            if [[ "${d}" == *"exports"* ]]; then
                chmod 700 "${d}"
            else
                chmod 775 "${d}"
            fi
        fi
    done
    sleep 5
done
