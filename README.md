# UniFi Video Controller (modernized)

A drop-in modernized container image for the (frozen, EOL) UniFi Video
Controller 3.10.13, targeting Synology DSM 7.x and other modern Docker
hosts.

[![build](https://github.com/conmilo/unifi-video-controller/actions/workflows/build.yml/badge.svg)](https://github.com/conmilo/unifi-video-controller/actions/workflows/build.yml)
[![release](https://github.com/conmilo/unifi-video-controller/actions/workflows/release.yml/badge.svg)](https://github.com/conmilo/unifi-video-controller/actions/workflows/release.yml)
[![license](https://img.shields.io/github/license/conmilo/unifi-video-controller)](LICENSE)

This is a fork of [pducharme/UniFi-Video-Controller](https://github.com/pducharme/UniFi-Video-Controller)
that swaps the deprecated base layer and runtime stack for a current,
supply-chain-pinned one, while preserving the original ports, environment
variables, and mount points so existing deployments can swap in place.

Additional inspiration / patches borrowed from
[vuhuy/unifi-video-docker](https://github.com/vuhuy/unifi-video-docker).
Both upstream authors are credited in `CHANGELOG.md` and `LICENSE`.

> **UniFi Video itself is EOL.** Ubiquiti retired the product in 2020
> and no Ubiquiti updates will ever ship.  This project modernizes the
> base OS, JRE, database, and build pipeline; swaps in current versions
> of every vulnerable bundled library (jackson, log4j, tomcat,
> commons-*, etc.) with airvision's Manifest Class-Path rewritten in
> lockstep at container start; and applies targeted ASM bytecode
> rewrites to `airvision.jar` itself so it loads on a modern JRE and
> calls Tomcat 9 APIs that replaced the Tomcat 7 instance methods it
> was originally compiled against -- see the
> [JRE history](#jre-history) section below for the empirical
> backstory.  What we still can't fix is application-logic bugs in
> Ubiquiti's source: we don't have it.  Migrate to UniFi Protect if
> you can.

---

## What's modernized

| Layer | Upstream (pducharme) | This fork |
|---|---|---|
| Base image | `phusion/baseimage:0.11` (Ubuntu 18.04, EOL 2023) | `ubuntu:24.04` LTS pinned by digest |
| JRE | Ubuntu's `openjdk-8-jre-headless=8u162-b12-1` (frozen 2018) | Canonical OpenJDK 21 LTS (apt-installed) + uv-patcher runtime tool that rewrites `airvision.jar`'s spec-illegal identifiers at container start (see [Security § JRE history](#jre-history)). Earlier releases (v3.10.13-4 .. -12) pinned AdoptOpenJDK 8u265-b01; the pin was retired in v3.10.13-13. |
| MongoDB | 4.0.x from MongoDB's deprecated bionic apt repo | 4.4.29 runtime + 4.2.25 fCV stepper |
| libssl1.1 | (implicit in base) | `openssl11-libs-1.1.1zg-1.amzn2.0.1` from Amazon Linux 2 ([ALAS2-2026-3249](https://alas.aws.amazon.com/AL2/ALAS2-2026-3249.html)); same build AWS CLI v2 itself ships.  Phase 7 (v3.10.13-18) switched from the focal-security 1.1.1f deb whose post-1.1.1f-1ubuntu2.24 CVE backports went paywalled-ESM-only |
| log4j | 2.17.0 | 2.26.0 (Maven Central; closes CVE-2021-44832 + the Phase 4 set of CVE-2025-68161 / CVE-2026-34477 / -34479 / -34480.  Phase 3.1 sourced 2.19.0 from apt's `liblog4j2-java`; Phase 4 reverted to Maven Central because apt's package is pinned at 2.19.0 in BOTH noble and resolute.) |
| BouncyCastle | 1.60 (`jdk15on` family, .deb-bundled) | 1.84 (`jdk18on` from Maven Central; closes 7 CVEs across `bcprov` + `bcpkix` -- CVE-2026-5588, CVE-2025-8916, CVE-2024-30171/29857, CVE-2023-33202, CVE-2020-26939/15522; same drop AWS SDK v2 and Apache Tomcat 11 GA bundle).  `bcprov-ext` retired (discontinued by upstream at 1.78.1; airvision uses only standard NIST P-256/P-384 + RSA which all live in main `bcprov`).  `bctls` retired (airvision registers `BouncyCastleProvider` only -- not `BouncyCastleJsseProvider` -- and JDK 21's native JSSE handles the :7443/:7442 connectors per Phase 3.4).  `bcutil-jdk18on` added as the transitive dep BC moved out of `bcprov` in 1.71+.  Phase 5 (v3.10.13-19). |
| Init | `runit` (phusion baseimage) | `tini` PID 1 |
| Supply chain | Untracked downloads | Every third-party artifact (MongoDB tarballs, UniFi Video deb, log4j/jackson/Maven Central JARs) SHA256-pinned in `checksums/SHA256SUMS` and verified before the runtime stage is built; `libssl.so.1.1` + `libcrypto.so.1.1` come from an image-digest-pinned Amazon Linux 2 stage with RPM GPG verification |
| Distribution | Docker Hub: [`pducharme/unifi-video-controller`](https://hub.docker.com/r/pducharme/unifi-video-controller) | `ghcr.io/conmilo/unifi-video-controller:<tag>` + GitHub Release `image.tar.gz` |
| CI | None | hadolint + buildx + Trivy on every PR; weekly base-image rebuild verification; monthly auto-release of date-stamped rebuilds |
| Updates | None | Dependabot watches the Ubuntu base digest and all GitHub Actions versions |

The MongoDB upgrade is the only step that **mutates your dataset**: on
first start of a container against an existing UniFi Video 3.10.13 data
dir, `migrate-mongo.sh` walks the featureCompatibilityVersion from 4.0
through 4.2 to 4.4. This is one-way -- see [docs/MIGRATION.md](docs/MIGRATION.md)
for the mandatory pre-swap snapshot procedure.

---

## Quick start

### Docker Compose (recommended)

```bash
git clone https://github.com/conmilo/unifi-video-controller.git
cd unifi-video-controller
# Edit docker-compose.yaml for your TZ, PUID/PGID, and host paths.
docker compose up -d
docker compose logs -f
```

### Plain `docker run`

```bash
docker run -d \
  --name unifi-video \
  --restart unless-stopped \
  --cap-add DAC_READ_SEARCH \
  -p 1935:1935/tcp \
  -p 6666:6666/tcp \
  -p 7004:7004/udp \
  -p 7080:7080/tcp \
  -p 7442:7442/tcp \
  -p 7443:7443/tcp \
  -p 7444:7444/tcp \
  -p 7445:7445/tcp \
  -p 7446:7446/tcp \
  -p 7447:7447/tcp \
  -p 10001:10001/udp \
  -v /volume1/docker/unifi-video/data:/var/lib/unifi-video \
  -v /volume1/docker/unifi-video/videos:/var/lib/unifi-video/videos \
  --tmpfs /var/cache/unifi-video \
  -e TZ=America/Los_Angeles \
  -e PUID=99 \
  -e PGID=100 \
  -e UMASK=002 \
  -e CREATE_TMPFS=no \
  -e DEBUG=0 \
  ghcr.io/conmilo/unifi-video-controller:latest
```

After ~3-4 minutes (first start includes the MongoDB fCV migration), the
web UI is at `https://<host>:7443/`.

### Synology DSM (Container Manager)

Each tagged release attaches `image.tar.gz` of the image to the GitHub
Release. The simplest GUI-only first-install path is:

1. Download `image.tar.gz` from the latest release to your PC:
   ```
   https://github.com/conmilo/unifi-video-controller/releases/latest/download/image.tar.gz
   ```
2. Copy it onto the NAS (File Station, SMB share, or `scp`).
3. Container Manager -> **Image** -> **Add** -> **Add From File** ->
   select the uploaded `image.tar.gz`.

DSM's **Add From URL** does *not* accept HTTPS file URLs, and adding
`https://ghcr.io` directly as a custom registry fails DSM's
connectivity test (GHCR doesn't implement the `/v2/_catalog` endpoint
DSM probes).  If you want recurring GUI-based updates from GHCR
without the file-import round-trip, run a tiny `registry:2`
pull-through cache on the NAS and add *that* as the custom registry
-- DSM's Container Manager flows then work end to end.  See
[docs/MIGRATION.md §3 Method C](docs/MIGRATION.md#c-ghcr-pull-through-cache-gui-only-recurring-updates)
for the step-by-step, and
[docs/MIGRATION.md §3.1](docs/MIGRATION.md#31-dsm-gotchas-paths-that-look-obvious-but-dont-work)
for the gotchas this avoids.

See [docs/MIGRATION.md](docs/MIGRATION.md) for the full DSM swap procedure
including the mandatory pre-swap snapshot.

---

## Configuration

### Environment variables

| Variable | Default | Description |
|---|---|---|
| `TZ` | `Etc/UTC` | Container timezone (affects log timestamps and the controller's scheduling logic). |
| `PUID` | `99` | UID the in-container `unifi-video` user adopts. Match this to the owner of your bind-mount dirs. |
| `PGID` | `100` | GID for the `unifi-video` group. |
| `UMASK` | `002` | `002` -> 775 dirs / 664 files. `022` -> 755/644. Other values: no chmod applied. |
| `DEBUG` | `0` | Set to `1` to pass `--debug` to `/usr/sbin/unifi-video`. |
| `CREATE_TMPFS` | `no` | When `yes`, the patched unifi-video script creates an internal tmpfs at `/var/cache/unifi-video`. Leave `no` if you use Docker's `--tmpfs` mount (recommended). |
| `USE_HOST_TMPFS` | `no` | vuhuy compatibility knob. |
| `USE_UNIFI_TMPFS` | `no` | vuhuy compatibility knob. |

### Ports

| Port | Proto | Purpose |
|---|---|---|
| 1935 | TCP | RTMP |
| 6666 | TCP | Inbound camera streams |
| 7004 | UDP | UVC-Micro talkback (camera side) |
| 7080 | TCP | HTTP web UI |
| 7442 | TCP | Camera management (NVR side) |
| 7443 | TCP | HTTPS web UI + API |
| 7444 | TCP | RTMPS |
| 7445 | TCP | Video over HTTP |
| 7446 | TCP | Video over HTTPS |
| 7447 | TCP | RTSP |
| 10001 | UDP | Camera discovery (Inform protocol) |

### Volumes

| Container path | Purpose |
|---|---|
| `/var/lib/unifi-video` | Controller state: MongoDB data, config, log files, recording index. Persist this. |
| `/var/lib/unifi-video/videos` | Recordings. Persist separately so you can use cheaper bulk storage. |
| `/var/cache/unifi-video` | Scratch dir for exports / HLS segments. Backed by `tmpfs` to spare SSD writes. |

---

## DSM swap procedure

See **[docs/MIGRATION.md](docs/MIGRATION.md)** for the full step-by-step including
the **mandatory pre-swap snapshot** (the MongoDB fCV bump is forward-only).

---

## Building from source

```bash
git clone https://github.com/conmilo/unifi-video-controller.git
cd unifi-video-controller
docker buildx build --platform linux/amd64 --load -t local/unifi-video-controller:dev .
```

The build downloads ~600 MB of pinned artifacts and verifies them against
`checksums/SHA256SUMS` before assembling the runtime image. Any tampering
or upstream drift fails the build immediately. End-to-end builds finish
in ~5 min on a warm cache.

### Updating the pinned base image digest

`Dockerfile`'s `ARG UBUNTU_DIGEST` references a specific
`ubuntu:24.04@sha256:...`. Dependabot auto-PRs new digests weekly. To
update manually:

```bash
docker pull ubuntu:24.04
docker inspect --format '{{ index .RepoDigests 0 }}' ubuntu:24.04
# Copy the sha256: portion into Dockerfile's UBUNTU_DIGEST ARG.
```

### Updating pinned artifacts

If a manually-fetched artifact (MongoDB, UniFi Video deb,
log4j-slf4j-impl, etc.) changes, update the URL in `Dockerfile` and the
corresponding line in `checksums/SHA256SUMS`. The CI build will
reject anything that doesn't match.  The `libssl.so.1.1` + `libcrypto.so.1.1`
pair has a separate pin via the `AL2_DIGEST` ARG and the
`OPENSSL11_LIBS_VERSION` build arg in the `libssl11-source` stage;
bumping these requires verifying against the latest ALAS advisory at
`alas.aws.amazon.com/alas2.html`.  Most JVM-side libraries (the JRE
itself, log4j 2.x, commons-collections, jettison, JAXB) come from
apt now -- Dependabot watches the noble apt repo and bumps in lockstep
with Canonical's security pool.  Bumping across LTS major versions
(e.g. OpenJDK 21 -> 25) is a deliberate code change
because the `uv-patcher` tool's bytecode rewrite must remain
compatible with the target class-file version (ASM 9.7 supports up
to class file v65 / Java 21).  See the [JRE history](#jre-history)
section below for the empirical backstory of the earlier 8u265 pin.

---

## Security

### Build pipeline

- Trivy scans every PR for HIGH/CRITICAL CVEs and fails on findings.
- SARIF results are uploaded to the GitHub Security tab so anyone can
  audit the current image's CVE status.
- Each release attaches an SPDX-JSON SBOM (`image.spdx.json`) and the
  multi-arch index digest is signed via in-toto attestations.
- Repository SHA256SUMS pins all third-party downloads before the
  runtime stage of the multi-stage Dockerfile copies them.
- The container does **not** run as root by default once the entrypoint
  has applied PUID/PGID. The `runit`-style root-then-drop dance in
  upstream is preserved but happens before the JVM is launched.

Security disclosures: open an issue. There is no published embargo
process for this fork beyond standard GitHub Security advisories.

### JRE history

The current JRE is **Canonical OpenJDK 21 LTS** (HotSpot, apt-installed
as `openjdk-21-jre-headless`).  Phase 3 (`v3.10.13-13`) retired the
AdoptOpenJDK 8u265-b01 pin that earlier releases required.  The story:

UniFi Video's bundled `airvision.jar` is obfuscated with class and
method names that violate JVM Spec section 4.2.2 (literal `.` in
method names like `new.super`; class names that are Java reserved
words like `super`, `Object`, `String`).  OpenJDK HotSpot's class-file
parser tightened its enforcement of these spec rules in `jdk8u272-b10`
(October 2020, as part of CVE-2020-14803 / JDK-8246383).  Every
HotSpot 8 build since then -- and every Eclipse OpenJ9 build at any
version, since OpenJ9 enforces JVM Spec identically -- rejects the
JAR at parse time with `java.lang.ClassFormatError`.  The check is in
the parser, not the verifier, so `-Xverify:none` / `-noverify` do not
help; no published JVM flag bypasses it.  We verified this empirically
(Eclipse Temurin 8u492 / Apr 2026 and IBM Semeru 8u482 OpenJ9 0.57.0
both reject; AdoptOpenJDK 8u265 accepts).  Releases `v3.10.13-4` ..
`v3.10.13-12` pinned 8u265 for this reason; see
[CHANGELOG.md § v3.10.13-4](CHANGELOG.md#v310134----pin-pre-strict-parser-hotspot-start-mongod-in-runsh)
for the full investigation.

**Phase 3 changed this** by introducing `uv-patcher` -- a small
ASM-based Java tool that scans `airvision.jar` at container start and
rewrites every spec-illegal identifier it finds via deterministic
escape rules.  In practice this hits ~130+ paths -- the v3.10.13-13
initial spec hand-curated only six (the `com/ubnt/A/super/oOOO/`
bundle), but the first smoke build revealed that Ubiquiti's
obfuscator emits illegal class paths scattered throughout
`com/ubnt/airvision/` as well; auto-discovery handles all of them by
construction and survives future obfuscation pattern changes without
spec edits.  Class simple names that are Java reserved words or
`java.lang` types (`super` / `Object` / `String`) become `ZSuper` /
`ZObject` / `ZString`; package segments that are keywords (`super` /
`class` / `return`) become `Zsuper` / `Zclass` / `Zreturn`; method
or field names containing literal `.` or matching keywords get
renamed to JLS-legal substitutes (`new.super` -> `new_super`,
`new` -> `znew`); every constant-pool reference is updated in
lockstep via ASM's `ClassRemapper`.  The rewrite happens in the
running container's writable layer **only** -- the image layer
always carries pristine Ubiquiti bytes, so the image's
`airvision.jar` matches Ubiquiti's published SHA256.  See
[`uv-patcher/README.md`](uv-patcher/README.md) for the design and the
committed rename specification (`uv-patcher/src/main/resources/airvision-renames.json`).

With the spec-illegal identifiers gone, the airvision JAR loads
cleanly on any modern JRE.  Canonical OpenJDK 21 was chosen as the
current LTS, sourced from apt's `openjdk-21-jre-headless` so the JRE
flows through the same Canonical security-update pipeline as the rest
of the OS packages (LTS support aligned with Ubuntu 24.04's window
to Apr 2029).  Monthly rebuilds now advance the JRE within the 21 LTS
line automatically via apt, the same way they advance the Ubuntu base.
The `libssl.so.1.1` + `libcrypto.so.1.1` pair is bumped via Dependabot
watching the Amazon Linux 2 image digest + manual `OPENSSL11_LIBS_VERSION`
refresh against the latest ALAS advisory.

The same `uv-patcher` pass ALSO handles airvision's two dangling
Tomcat 9 Bootstrap call sites.  Tomcat 9 reduced
`Bootstrap.setCatalinaBase(String)` and
`Bootstrap.setCatalinaHome(String)` to no-arg statics (the values
now come from system properties read in the static initialiser),
but airvision's obfuscated `com/ubnt/common/oOOO/A.<init>` was
compiled against Tomcat 7 and still calls them via `INVOKEVIRTUAL`.
Releases `v3.10.13-11` and `v3.10.13-12` worked around this by baking
a shim into a patched `tomcat-embed-core.jar` at image build time
(re-adding the two methods).  Phase 3 (`v3.10.13-13`) moved the
shim to runtime so the image layer's `tomcat-embed-core-9.0.118.jar`
stays pristine.  Phase 3.5 (`v3.10.13-15`) retired the shim entirely
by rewriting the two call sites in airvision's own bytecode --
`INVOKEVIRTUAL setCatalinaBase/Home` becomes `INVOKESTATIC
java/lang/System.setProperty("catalina.{base,home}", arg)`, which
is the semantic intent of the original call.  The result:
`tomcat-embed-core-9.0.118.jar` is now byte-pristine in BOTH the
image layer AND the running container, matching its Maven Central
SHA256 byte-for-byte.  Trivy fingerprints it cleanly without the
SHA1-mismatch `.trivyignore` entries those earlier releases needed.

---

## Credits

- **[pducharme/UniFi-Video-Controller](https://github.com/pducharme/UniFi-Video-Controller)**
  -- original `Dockerfile`, `run.sh`, `docker-compose.yaml`, and the
  `unifi-video.patch` that disables the in-container `ulimit` calls and
  swaps `hostname -I` for `hostname -i`. This fork is a literal GitHub
  fork preserving that history.
- **[vuhuy/unifi-video-docker](https://github.com/vuhuy/unifi-video-docker)**
  -- the `USE_HOST_TMPFS` / `USE_UNIFI_TMPFS` env-var contract and
  several DSM-specific hints surfaced in MIGRATION.md.
- **Ubiquiti Inc.** -- author of UniFi Video itself, distributed under
  Ubiquiti's EULA. This project does not redistribute the UniFi Video
  `.deb`; it's fetched from `dl.ubnt.com` at build time.
- **MongoDB Inc., Eclipse Foundation Adoptium, Canonical, Apache
  Foundation** -- upstream sources for the third-party artifacts.

---

## License

[MIT](LICENSE), with dual copyright (pducharme upstream + conmilo
modernization).

The MIT license applies only to the build tooling, scripts, and
documentation in this repository.  It does **not** grant any rights
over the embedded third-party software the runtime image carries:

- **UniFi Video** itself remains the property of Ubiquiti Inc.,
  licensed under Ubiquiti's EULA.  This project does not redistribute
  the UniFi Video `.deb`; it's fetched from `dl.ubnt.com` at build
  time.
- **MongoDB Community Server** -- SSPL (MongoDB Inc.).
- **Canonical OpenJDK 21 JRE** -- GPLv2 with Classpath Exception.
- **OpenSSL** -- Apache License 2.0 upstream; Ubuntu's packaging
  carries its own.
- **Apache Tomcat embed**, **log4j 2.x**, **Jackson**, **Guice**,
  **commons-collections** etc. -- Apache License 2.0.

Consult each upstream project for the terms governing their use.
