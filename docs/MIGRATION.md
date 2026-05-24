# Migration: swap pducharme's UniFi Video container for the modernized image (Synology DSM)

This document covers the **in-place swap** on a Synology NAS running
Container Manager (originally written + verified against DSM 7.3.x).
The procedure assumes you have an existing UniFi Video container based
on `pducharme/unifi-video-controller` (or an equivalent fork) with
persistent volume mounts you want to keep.

> **READ THIS FIRST.** Step 1 (snapshot) is **MANDATORY**, not optional.
> The modernized image runs MongoDB 4.4. MongoDB's
> `setFeatureCompatibilityVersion` upgrade from 4.0 -> 4.2 -> 4.4 is
> **one-way**: there is no downgrade path. If anything goes wrong after
> the migration runs, your *only* rollback is the snapshot you took
> before starting. There are no exceptions to this rule.

---

## 0. Inventory: things you'll need to know

Before you start, write these down somewhere outside the container:

| Item | How to find it |
|---|---|
| Name of your current UniFi Video container | DSM -> Container Manager -> Container list. |
| Host paths bound into `/var/lib/unifi-video` and `/var/lib/unifi-video/videos` | DSM -> Container -> select container -> Action -> **Details** -> Volume tab. |
| PUID / PGID currently in use | DSM -> Container -> select container -> Action -> **Details** -> Environment tab. |
| Current container image name + tag | Same Details panel, top of the General tab. |

You should also confirm your DSM admin can reach the controller's web UI
**before** the swap. If it's already broken, fixing it post-swap will be
ambiguous (is it a swap problem or the original problem?).

---

## 1. Pre-swap snapshot (MANDATORY)

Do **at least one** of the following. Both is better. The goal is a
point-in-time copy of the entire UniFi Video data directory that you
can restore if the fCV migration step in §6 fails.

### Option A: Snapshot Replication (preferred if available)

1. DSM -> **Snapshot Replication** -> **Snapshots** tab.
2. Pick the shared folder that holds your bind-mount target (e.g.
   `/volume1/docker/unifi-video`).
3. Click **Take a Snapshot**. Label it `pre-modernize-YYYY-MM-DD`.
4. Verify it appears in the list and is not 0 KiB.

### Option B: File-level copy

If your data lives somewhere Snapshot Replication can't reach, or you
don't have a Btrfs volume:

1. DSM -> Container Manager -> stop the existing UniFi Video container.
2. SSH into the NAS as an account with permissions on the bind-mount
   dir.
3. `cp -aR --reflink=auto /volume1/docker/unifi-video /volume1/docker/unifi-video.before-modernize`
   (or `rsync -a` if reflink unsupported).
4. Confirm the copy is the same size as the original
   (`du -sh /volume1/docker/unifi-video*`).

This becomes your rollback dataset. **Do not skip.**

---

## 2. Export the existing container's settings

DSM -> Container -> select your existing UniFi Video container ->
**Action** -> **Settings** -> **Export**.

Save the resulting `.json` to a location outside the container's volume.
This captures all your custom ports, env vars, and capabilities so you
can recreate the container if **Edit image** fails (a known DSM 7.3
nuisance).

---

## 3. Obtain the new image

Pick whichever fits your setup. **Method A is the recommended path for
DSM 7.3** because it's GUI-only and avoids two Container Manager quirks
documented in §3.1.

### A. Download + import via Container Manager GUI (recommended)

1. On your PC, download `image.tar.gz` from the latest release:
   `https://github.com/conmilo/unifi-video-controller/releases/latest/download/image.tar.gz`
   (~975 MB).
2. Copy it onto the NAS using File Station, an SMB mount, or `scp` --
   e.g. drop it into `\\<nas>\docker\` over SMB.
3. DSM -> Container Manager -> **Image** tab -> **Add** ->
   **Add From File** (older Container Manager versions label this
   **Import**).
4. Browse to the uploaded `image.tar.gz` -> **Open**. DSM loads it in
   ~1-2 minutes.
5. The image `ghcr.io/conmilo/unifi-video-controller:v3.10.13-1` now
   appears in the Image list.

If your Container Manager rejects the `.tar.gz` ("unsupported file
format" -- happens on older builds), decompress to plain `.tar` first
with 7-Zip on Windows or `gunzip` on the NAS, then import the `.tar`.

### B. GHCR pull via SSH (no PC -> NAS round-trip)

Fastest if you don't mind enabling SSH; the NAS pulls the ~870 MB image
directly from GHCR over its own internet link.

1. DSM -> Control Panel -> Terminal & SNMP -> enable **SSH service**.
2. From your PC:
   ```bash
   ssh admin@<nas-ip>
   sudo docker pull ghcr.io/conmilo/unifi-video-controller:v3.10.13-1
   ```
3. Disable SSH again in Control Panel (good hygiene).
4. Image now visible under Container Manager -> Image.

### C. SSH + `.tar.zst` (smallest transfer)

`image.tar.zst` is ~10% smaller than the `.tar.gz` but DSM's GUI doesn't
recognise it. Use this only if you're already SSH'd in and care about
the saved megabytes:

1. Download `image.tar.zst` to your workstation.
2. `scp image.tar.zst admin@<nas-ip>:/volume1/docker/`
3. On the NAS:
   ```bash
   ssh admin@<nas-ip>
   sudo docker load -i <(zstd -d -c /volume1/docker/image.tar.zst)
   rm /volume1/docker/image.tar.zst
   ```

### 3.1 DSM gotchas (paths that look obvious but don't work)

Two routes that *seem* like they should work on DSM 7.3 Container
Manager but won't -- documented so you don't waste time on them:

- **Image -> Add -> Add From URL** pointed at the GitHub release
  tarball URL (e.g.
  `https://github.com/.../releases/.../image.tar.gz`).
  DSM rejects with `Invalid Docker Repository URL`. "Add From URL"
  expects a *Docker registry reference* (like `ghcr.io/owner/name:tag`),
  not an HTTPS link to a file. Pasting the registry reference itself
  works on some Container Manager versions and fails on others -- if
  you want to try, paste exactly
  `ghcr.io/conmilo/unifi-video-controller:v3.10.13-1` (no `https://`,
  no path). If that fails, fall back to Method A.

- **Registry tab -> Settings -> Add custom registry** pointed at
  `https://ghcr.io`. DSM's connectivity test calls the registry's
  `/v2/_catalog` endpoint, which GHCR deliberately does not implement
  (GHCR images are pullable but not browseable). DSM reports
  `Unable to connect to the registry` even though `docker pull` against
  the same hostname works fine. There is no DSM-side workaround --
  use Method B (SSH pull) or Method A (file import) instead.

---

## 4. Stop the old container

DSM -> Container Manager -> select old container -> **Action** -> **Stop**.

Wait for state to go from "Stopping" -> "Stopped" before continuing.
**Do not delete the container yet** -- if §6 reveals a problem we'll
restart this one as our quickest rollback.

---

## 5. Create the new container

**DSM 7.3 Container Manager has no "Edit image" feature on an existing
container.** "Duplicate" copies the container but inherits the old
image and doesn't let you change it either. The only working path is
to recreate the container from the JSON you exported in §2 with two
manual edits.

### Path A (only viable path on DSM 7.3): Import the edited JSON

1. Open the `.json` you exported in §2 in any text editor.
2. Change the `image` field:
   ```json
   "image" : "ghcr.io/conmilo/unifi-video-controller:v3.10.13-2"
   ```
   (was something like `pducharme/unifi-video-controller:latest`).
3. Change the `id` field to any unique 64-char lowercase hex string
   that doesn't collide with an existing container's ID. Easiest
   options:
   - Flip one character of the existing ID (e.g. leading `b` -> `c`).
   - Generate a fresh one in PowerShell on your PC:
     ```powershell
     -join ((1..64) | ForEach-Object { '{0:x}' -f (Get-Random -Maximum 16) })
     ```
   - Delete the line entirely; some DSM Container Manager versions
     regenerate it on import.
4. Optionally bump the `name` field (e.g. `unifi-video-modernized`)
   so the old container can keep its original name during your §7
   verification window.
5. Save.
6. DSM -> Container Manager -> **Container** -> **Create** ->
   **Import** -> upload the edited JSON.
7. The wizard walks you through the imported settings -- accept all
   of them. The image field should already show
   `ghcr.io/conmilo/unifi-video-controller:v3.10.13-2`. Confirm the
   volume mounts, port mappings (all 11), and environment variables
   are intact.
8. Finish the wizard, but **do not start yet** -- §6 has the migration
   log you need to watch.

### Path B (manual wizard fill, no exported JSON): if §2 export failed

If for some reason §2 didn't produce a usable JSON, you can also do
this from scratch via **Container** -> **Create** -> picking
`ghcr.io/conmilo/unifi-video-controller:v3.10.13-2` and filling in
the wizard by hand. You'll need to re-enter:

- Volume bind mounts (`/var/lib/unifi-video` and any `videos` override).
- All 11 port mappings (TCP 1935, 6666, 7080, 7442, 7443, 7444, 7445,
  7446, 7447 + UDP 7004, 10001).
- Environment variables (`PUID`, `PGID`, `UMASK`, `TZ`,
  `CREATE_TMPFS=no` and any overrides you had).
- Advanced -> Capability: add `DAC_READ_SEARCH`.
- Enable auto-restart.

---

## 6. First start: watch the migration

The very first start of the modernized container against an existing
UniFi Video data dir kicks off `migrate-mongo.sh`. It walks the MongoDB
fCV from 4.0 -> 4.2 -> 4.4. Expected duration: 30s -- 3 min for a
typical 10-100 GB dataset.

### Watch the logs in real time

```bash
ssh admin@nas
sudo docker logs -f <new-container-name>
```

You should see something like:

```
[migrate-mongo] No existing MongoDB data dir at /var/lib/unifi-video/db, skipping fCV migration.
...
```

**OR** (if you have existing data):

```
[migrate-mongo] Starting mongod 4.4 (probe) from /opt/mongodb-4.4/bin/mongod against /var/lib/unifi-video/db...
[migrate-mongo] mongod 4.4 reports current fCV='4.0'. Falling back to 4.2 stepper.
[migrate-mongo] Starting mongod 4.2 (bridge)...
[migrate-mongo] Setting featureCompatibilityVersion to 4.2...
[migrate-mongo] Shutting down mongod 4.2 (bridge)... done.
[migrate-mongo] Starting mongod 4.4 (finalize)...
[migrate-mongo] Setting featureCompatibilityVersion to 4.4...
[migrate-mongo] Migration complete: fCV is now 4.4.
```

Followed by:

```
Starting unifi-video... done.
Waiting for mongod to come online... done.
```

If you see `Migration complete: fCV is now 4.4.` -- you're past the
point of no return. Snapshot is now your only fallback to a 4.0 state.

If you see `[error] MongoDB fCV migration failed.` -- jump to §8
(Rollback) immediately. **Do not** retry or "fix and restart"; restore
the snapshot first.

### Subsequent starts

`migrate-mongo.sh` is idempotent: subsequent restarts find fCV == 4.4
and exit in <2 seconds.

### Phase 3 (v3.10.13-13) note: OpenJDK 21 + uv-patcher

Releases `v3.10.13-13` and later ship Canonical OpenJDK 21 LTS in place
of the AdoptOpenJDK 8u265 pin earlier releases needed.  The JRE comes
from Ubuntu noble's `openjdk-21-jre-headless` apt package (currently
21.0.10) -- apt-managed CVE patching, no manual Adoptium tarball
fetch.  The transition requires **no data migration** -- the JVM swap
is invisible to MongoDB, the bind-mount layout, and the existing
container settings.
On first start, `run.sh` invokes the `uv-patcher` tool (built into the
image at `/opt/uv-patcher/`) which rewrites `airvision.jar`'s
spec-illegal identifiers and applies the Tomcat 9 Bootstrap shim in
the running container's writable layer.  Both rewrites are
idempotent, so restart is safe.  Expect first-start time to grow by
~10-15 seconds vs. v3.10.13-12 (the patcher takes ~5s per JAR; the
combined OpenJDK 21 + module-system warmup adds a few more seconds).

To **upgrade in place** from any v3.10.13-N (N >= 7): `docker pull`
the new tag, `docker compose down && up -d`.  No volume changes, no
fCV step, no `.trivyignore` adjustments on the user side.  Rollback
is a tag downgrade -- the image always carries pristine Ubiquiti
bytes, so swapping back to a v3.10.13-12 image works without data
recovery.

---

## 7. Verify the controller is healthy

1. Web UI reachable at `https://<nas-ip>:7443/`. Cert warning is
   expected (self-signed).
2. Existing cameras connect: in the controller UI, check each camera's
   status. They re-register automatically over port 7080.
3. Recordings continue: pick a camera with continuous recording and
   confirm a new clip appears within 5 min in the web UI's timeline.
4. Storage usage matches your expectation (no surprise re-indexing).

If all four check out, you're done. Delete the old container:

DSM -> Container -> old container -> **Action** -> **Delete**.

You can keep the old image around in DSM's image list for a week or two
as belt-and-suspenders, then delete it once you're confident.

---

## 8. Rollback procedure

If verification in §7 fails or migration in §6 errored:

1. **Stop the new container immediately** (DSM -> Container ->
   **Stop**).
2. **Restore the snapshot** from §1:
   - Snapshot Replication path: DSM -> Snapshot Replication ->
     Snapshots -> select `pre-modernize-YYYY-MM-DD` ->
     **Action** -> **Restore**.
   - File-copy path: stop the new container, then
     `rm -rf /volume1/docker/unifi-video && mv /volume1/docker/unifi-video.before-modernize /volume1/docker/unifi-video`.
3. **Start the old container** (still in DSM's container list from §4).
4. Verify the old setup is working again.
5. Open a GitHub issue at
   `https://github.com/conmilo/unifi-video-controller/issues` with the
   container logs (`docker logs <container>` from §6) attached.

The new container can stay stopped -- DSM doesn't charge you for it.
Delete it once you've confirmed the rollback.

---

## 9. Common gotchas

| Symptom | Likely cause | Fix |
|---|---|---|
| Web UI unreachable after start, mongod logs OK | DSM firewall blocking 7443/TCP | DSM -> Control Panel -> Security -> Firewall: allow inbound on the 11 ports listed in §5. |
| Cameras stuck "Disconnected" but used to work | UDP port 10001 not mapped (new in this image vs. some old configs) | Add `-p 10001:10001/udp` to the container -- this is the camera discovery port that several earlier forks omitted. |
| `chown failed` warnings during start | Bind-mount dir owned by an unexpected UID | Set `PUID` / `PGID` env vars to match the actual owner. Check with `stat /volume1/docker/unifi-video`. |
| `mongod did not accept connections within 120s` during migration | Slow SSD or extremely large dataset | Migration *might* still succeed silently -- check the logs for the `Migration complete` line. If it really stalled, restore snapshot and open an issue with the migrate-mongod.log file (`/var/log/unifi-video/migrate-mongod.log` inside the container). |
| `Healthcheck failed` for the first 4 minutes | Cold start + fCV migration | This is normal. The Dockerfile sets `--start-period=240s` for exactly this. |
