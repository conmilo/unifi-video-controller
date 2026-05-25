# Changelog

All notable changes to this fork are documented here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versioning is
`v<UniFi Video version>-<our patch level>[.<YYYY-MM>]` (e.g.
`v3.10.13-1` for the initial modernization, `v3.10.13-1.2026-06` for a
monthly auto-rebuild without code changes).

## [v3.10.13-23] -- MongoDB 4.4.30 + prune redundant Java apt packages (closes 8 CVEs)

### TL;DR

Two CVE-driven changes shipping together:

1. **MongoDB 4.4 runtime bump 4.4.29 -> 4.4.30**, closing
   **CVE-2025-14847 (HIGH)** in mongod's Zlib protocol decompression
   path.  Trivy didn't see this CVE because mongod is installed from
   a `.tgz` tarball without dpkg metadata; Grype's SBOM-mode scan
   surfaced it via the `pkg:generic/mongodb@4.4.29` PURL syft's
   binary cataloguer emits from the mongod ELF.  See
   [MongoDB security advisory](https://www.mongodb.com/community/forums/c/announcements/security-advisories)
   for the upstream vendor's bulletin.  Fix landed in 4.4.30 (and
   5.0.32 for the 5.0 line we don't ship).

2. **Prune redundant `lib*-java` apt packages after copying their
   JARs into airvision's classpath**, closing **7 CVEs** (1 CRITICAL,
   3 HIGH, 3 MEDIUM) in the libjaxb-java transitive dependency chain
   (dom4j 2.1.1, plexus-archiver 4.6.1, plexus-utils 3.4.2, commons-
   io 2.11.0, commons-compress 1.25.0, commons-lang3 3.14.0).  These
   JARs were on disk in `/usr/share/java/` but never on airvision's
   classpath -- the apt packages were only needed as the SOURCE for
   the install -m 400 step that copies the modern JAR bytes into
   `/usr/lib/unifi-video/lib/`.

**Total CVEs closed: 8 (1 CRITICAL + 4 HIGH + 3 MEDIUM).**

**Zero behavioural change** beyond the version bump.  Image bytes
for airvision's actual classpath (`/usr/lib/unifi-video/lib/`) are
identical to v3.10.13-22.  MongoDB wire protocol, WiredTiger
storage format, and fCV semantics are unchanged (4.4.29 -> 4.4.30
is a routine point release within the same major).  Image total
size: 2.91 GB -> 2.34 GB (~570 MB compressed savings from the
pruned apt chain).

The v3.10.13-20 `ARG MONGO44_VERSION` parameterisation was built
exactly for this kind of one-line CVE bump: change the default,
re-pin the SHA256, ship.

### How this was discovered

This CVE is a poster child for why Trivy alone leaves blind spots
that a layered scanner setup catches.  The investigation chain:

1. The runtime mongod binary at `/opt/mongodb-4.4/bin/mongod` is
   extracted from a `.tgz` tarball and copied bare into the image.
   It carries no dpkg / rpm metadata, so Trivy's OS-package scanner
   doesn't catalogue it at all.  This was visible as
   `trivy rootfs <mongod>` returning 0 results -- same blind spot
   shape as the Phase 7 libssl1.1 `.so` files.

2. The image SBOM (BuildKit syft, attached as attestation) DOES
   catalogue mongod via its binary cataloguer -- it reads the
   `db version v4.4.29` string from the ELF and emits
   `pkg:generic/mongodb@4.4.29`.  But Trivy's CVE matcher doesn't
   query its CVE DBs for `pkg:generic/*` PURLs (only ecosystem-
   specific ones like `pkg:deb/...`, `pkg:rpm/...`, `pkg:maven/...`),
   so the SBOM entry is listed-but-unsearched.

3. **Grype 0.112.0** (Anchore's vulnerability matcher, same syft
   data source) **does** match `pkg:generic/mongodb@4.4.29` against
   its CVE DB and surfaced CVE-2025-14847 with a clear `Fix:
   ['4.4.30', '5.0.32']` directive.

   The fix path is concretely actionable -- 4.4.30 exists at
   fastdl.mongodb.org (HTTP 200 verified) and is the last release
   before 4.4 EOL'd in Feb 2024.

The same Grype run also surfaced two MongoDB findings that ARE
false positives:

- **CVE-2017-2665 [HIGH] unfixed** -- describes "skyring-setup"
  writing a plaintext MongoDB password in Red Hat Storage Console /
  Satellite 6.  Not a MongoDB Inc. vulnerability; a Red Hat product
  integration issue.  Grype matches it via overly-broad version
  range.
- **CVE-2014-8180 [MEDIUM] unfixed** -- same Red Hat Satellite 6
  integration class; not a MongoDB Inc. CVE.

Both are noted here so the next maintainer doesn't chase
unactionable findings when running Grype.

### What about the 4.2.25 fCV stepper?

`/opt/mongodb-4.2/bin/mongod` (the fCV stepper used once by
`migrate-mongo.sh` during the 4.0 -> 4.2 -> 4.4 featureCompatibility
bridge) is affected by the same CVE class.  However:

- MongoDB 4.2 EOL'd April 2023, predating the public disclosure
  cycle that produced CVE-2025-14847.  No 4.2.x fix was ever
  published.
- The fCV stepper runs for ~5 seconds total during initial database
  migration, binds to a localhost-only socket, and exits before any
  external network is reachable.  CVE-2025-14847 requires an
  unauthenticated attacker to reach the wire protocol; the threat
  model doesn't intersect our usage.
- A future MongoDB 4.4 startup against a 4.0 dataset still needs
  the 4.2 stepper to bridge the fCV gap.  Removing the stepper
  would break that path.

Verdict: 4.2.25 stays.  Documented residual; not gated.  Grype will
continue to flag the 4.2.25 instance of CVE-2025-14847 -- expected
and accepted.

### Why not switch to the MongoDB apt repo?

Investigated as part of this work.  MongoDB Inc. publishes
`mongodb-org` apt repos at `https://repo.mongodb.org/apt/ubuntu`,
which would give us dpkg metadata that Trivy can scan.  But:

| Distro | mongodb-org 4.4 repo |
|---|---|
| focal (20.04) | published; 4.4.21 .. 4.4.30 available |
| jammy (22.04) | published |
| **noble (24.04)** | **NOT PUBLISHED (HTTP 404)** -- MongoDB Inc. never released 4.4.x packages for Ubuntu 24.04 |

Switching would require either downgrading the base image from
`ubuntu:24.04` to `ubuntu:22.04` (losing Ubuntu 24.04's LTS
security pool, supported through 2029) or pulling the focal/jammy
repo against noble (libc / libssl mismatch, unsupported).  Neither
is worth the marginal SBOM coverage win, especially when MongoDB
4.4 is EOL and no new CVEs are being filed against the line
anyway.

We stay on the `.tgz` tarball path.  The 4.4.30 bump above is the
same artifact source, just a different version pin.

### Apt JAR cleanup -- the seven CVE chain

Phase 3.2 (v3.10.13-13) added four `lib*-java` apt packages
(`libjaxb-api-java`, `libjaxb-java`, `libjettison-java`,
`libcommons-collections3-java`) to provide JAXB / JAF / jettison /
commons-collections3 JARs that airvision (a Java 8 build) imports
directly.  Without them on the Class-Path, the Guice filter's
annotation processing throws `TypeNotPresentException` at Tomcat
context startup and the entire web service 404s.

The Dockerfile's runtime stage has long contained an `install -m 400`
block that COPIES the JAR bytes from `/usr/share/java/` (where apt
puts them) into `/usr/lib/unifi-video/lib/` (where airvision's
`MANIFEST.MF Class-Path:` resolves them -- all entries are
relative names that resolve against airvision.jar's own location).

What got discovered during the v3.10.13-22 Grype-vs-Trivy
investigation: `libjaxb-java` drags in a heavy transitive chain via
`libistack-commons-java` -> `ant` + `ant-optional` + `libdom4j-java`
+ `libplexus-archiver-java` + `libmaven3-core-java` +
`libwagon-http-java` + `libcommons-{io,compress,lang3}-java` +
`libsisu-*` / `libmaven-*` family.  None of those JARs are on
airvision's classpath (`/usr/share/java/` is not in airvision's
`Class-Path:` line; JSVC only adds `commons-daemon.jar` from that
dir via the script's `-cp` flag).  But they were sitting in the
image filesystem AND in dpkg state, where Grype's SBOM-mode scan
flagged them for seven real CVEs (Trivy CI's image-mode scan was
missing them; the SBOM-mode scan via `trivy sbom image.spdx.json`
does catch them, suggesting a Trivy CI configuration gap that
could be a follow-on PR).

CVEs closed by this prune:

| CVE | Severity | Package | Description |
|---|---|---|---|
| CVE-2020-10683 | **CRITICAL** | dom4j 2.1.1 | XXE in default SAX parser |
| CVE-2024-47554 | HIGH | commons-io 2.11.0 | DoS via XmlStreamReader |
| CVE-2023-37460 | HIGH | plexus-archiver 4.6.1 | Arbitrary file creation in AbstractUnArchiver |
| CVE-2025-67030 | HIGH | plexus-utils 3.4.2 | Directory Traversal in extractFile |
| CVE-2024-25710 | MEDIUM | commons-compress 1.25.0 | Infinite loop on corrupted DUMP file |
| CVE-2024-26308 | MEDIUM | commons-compress 1.25.0 | OOM on broken Pack200 file |
| CVE-2025-48924 | MEDIUM | commons-lang3 3.14.0 | Uncontrolled Recursion |

Plus ~370 unfixed-Ubuntu transitive CVEs in the `ant` family that
Grype was flagging but that Canonical never patches (those
disappear from the SBOM too once the packages are gone).

### The install-+-copy-+-purge strategy

The runtime stage now follows this sequence:

  1. `apt-get install -y --no-install-recommends ... libjaxb-api-
     java libjaxb-java libjettison-java libcommons-collections3-
     java ...` (unchanged; restores the apt packages that the JAR-
     copy step depends on).

  2. `install -m 400 -o unifi-video -g unifi-video
     /usr/share/java/<name>.jar /usr/lib/unifi-video/lib/<name>.jar`
     for each JAR airvision actually loads (jaxb-api, jaxb-runtime,
     jaxb-core, javax.activation, istack-commons-runtime, stax-ex,
     txw2, jettison, commons-collections3-renamed-to-commons-
     collections).  This is the existing copy step from Phase 3.1 /
     3.2; unchanged.

  3. **NEW (v3.10.13-23)**: `apt-get -y purge libjaxb-api-java
     libjaxb-java libjettison-java libcommons-collections3-java
     && apt-get -y autoremove --purge` at the END of the same RUN
     block.  Since the JARs were COPIED (not symlinked) in step 2,
     they survive the purge.  dpkg state and `/usr/share/java/`
     filesystem state are both cleaned up.  Trivy, Grype, and SBOM
     attestations stop flagging the seven CVEs above.

  4. `rm -rf /var/lib/apt/lists/*` to clean the apt cache (unchanged).

Net effect:

- `/usr/share/java/` shrinks from ~80 JARs to 6 (only the still-
  needed `commons-daemon` for jsvc, plus a few minor `libintl` /
  `gettext` artifacts that aren't security-relevant).
- Image size: **2.91 GB -> 2.34 GB** (~570 MB compressed savings
  measured locally via `docker images`).
- `dpkg-query --list | wc -l` drops by ~70 packages.
- airvision's actual classpath is unchanged byte-for-byte;
  `md5sum` verified across all four PR-affected JARs.

### Why this is safe (audit trail)

Three independent verifications, in order of strength:

  1. **Static**: airvision's `MANIFEST.MF Class-Path:` referenced
     by `jar:` URL resolution.  Every entry is a relative name
     (e.g. `jaxb-api-2.3.1.jar`, not `/usr/share/java/jaxb-api-
     2.3.1.jar`); per the JAR spec, relative `Class-Path:` entries
     resolve against the manifest-bearing JAR's location, i.e.
     `/usr/lib/unifi-video/lib/`.  `/usr/share/java/` is not in the
     resolution path.

  2. **Empirical**: built the image locally, ran `docker run
     --cap-add DAC_READ_SEARCH -v /path/to/data:/usr/lib/unifi-
     video/data <image>`, observed airvision starting cleanly
     (jsvc + EMS main loop processes running, `unifi-video.pid`
     created, `server.log` writing fresh entries, EMS websocket
     connections establishing on port 7440).

  3. **Cross-checked**: built the v3.10.13-15 baseline locally and
     ran identical smoke -- same behaviour (jsvc starts only with
     `--cap-add DAC_READ_SEARCH`, fails silently without).  The
     `--cap-add` requirement is pre-existing (documented in README
     and docker-compose.yaml since at least v3.10.13-5); the apt
     cleanup doesn't change cap requirements.

### Files changed

- **`Dockerfile`**:
  - Header comment line 12: `default 4.4.29` -> `default 4.4.30`.
  - `ARG MONGO44_VERSION=4.4.29` -> `ARG MONGO44_VERSION=4.4.30` at
    the top-level ARG block.
  - Example comment line 49: `--build-arg MONGO44_VERSION=4.4.30`
    -> `--build-arg MONGO44_VERSION=4.4.29` (rotated so the example
    still demonstrates a real overridable target -- 4.4.29 stays
    downloadable from fastdl.mongodb.org indefinitely as a back-
    catalogue release).
  - Phase 3.2 comment block expanded to describe the install-+-
    copy-+-purge strategy and cite the seven CVEs the prune closes.
  - Apt-purge block appended to the end of the .deb-extract +
    JAR-install RUN: `apt-get -y purge libjaxb-api-java libjaxb-
    java libjettison-java libcommons-collections3-java; apt-get -y
    autoremove --purge; rm -rf /var/lib/apt/lists/*`.  Lives at the
    end of the existing big RUN block (no new layer added).
- **`checksums/SHA256SUMS`**: drop the 4.4.29 line, add
  `a2bf4c4db59fa4ad0b629fb598a3ff13257f71af82967ebcb49db7f0441131ca
  mongodb-linux-x86_64-ubuntu2004-4.4.30.tgz`.  Computed locally
  from the downloaded tarball; the fetcher stage re-verifies on
  every build.
- **`mongodb-server-equivs.control`**: `Version: 4.4.29` ->
  `4.4.30` plus the `Provides:` line's two `(= 4.4.29)` instances
  -> `(= 4.4.30)`.  The equivs-built stub package satisfies the
  unifi-video.deb's `mongodb-server | mongodb-org-server |
  mongodb-10gen` dependency without dpkg ever seeing the actual
  binary; the version field should still match what we ship.
- **`README.md`**: comparison table cell `4.4.29 runtime + 4.2.25
  fCV stepper` -> `4.4.30 runtime + 4.2.25 fCV stepper`.

### Files NOT changed (intentionally)

- **MongoDB 4.2.25 (`MONGO42_VERSION` ARG)**: stays at 4.2.25.  No
  4.2.x fix exists for CVE-2025-14847 (EOL since April 2023); the
  stepper's threat model doesn't intersect the CVE's exploit
  preconditions.  Documented residual above.
- **`mongo-java-driver-2.14.2.jar`** (inside `airvision.jar`): not
  affected.  CVE-2025-14847 is a server-side network-layer issue
  in mongod's Zlib decompression path; the driver doesn't expose
  the vulnerable surface.  The driver itself has zero CVEs in
  Trivy / Grype / NVD against 2.14.2 (Phase 6 reachability audit
  context applies: the driver is EOL but its public attack surface
  is closed).
- **Historical CHANGELOG entries**: kept verbatim referencing
  4.4.29.  Those describe what was true at their respective
  release; rewriting history would obscure the timeline.

### Verification

Pre-merge sanity:

- `curl -sIo /dev/null -w "%{http_code}\n" https://fastdl.mongodb.org/linux/mongodb-linux-x86_64-ubuntu2004-4.4.30.tgz`
  -> `200`.
- Local SHA256 of the downloaded tarball matches the new SHA256SUMS
  entry (`a2bf4c4db59fa4ad0b629fb598a3ff13257f71af82967ebcb49db7f0441131ca`).
- `hadolint Dockerfile`: clean (no regressions; same exemptions as
  v3.10.13-20).
- `docker buildx build --load`: TBD by maintainer; the fetcher
  stage will fail loudly if the SHA256 entry is wrong (the right
  failure mode for an unverified binary).

Post-merge smoke:

- `mongod --version` inside the runtime image should report
  `v4.4.30`.
- `migrate-mongo.sh` against an existing UV dataset: the 4.0 ->
  4.2 -> 4.4 fCV bridge still works (the 4.2.25 stepper is
  unchanged; only the 4.4 destination major changed by a patch
  level).
- Grype rescan against the published image SBOM: CVE-2025-14847
  should no longer match the 4.4.x binary entry; the 4.2.25
  finding remains as a documented residual.

### Residuals (post-merge)

- **0 unsuppressed alerts** in the Trivy CI image-mode gate
  (unchanged from v3.10.13-22; Trivy's image scan didn't see the
  mongod CVE and apparently doesn't surface the `/usr/share/java/`
  CVEs either -- see the `/usr/share/java/` Trivy gap note below).
- **0 SBOM-scan findings via `trivy sbom image.spdx.json`** for
  the seven `/usr/share/java/` CVEs (verified locally; the
  published image's SBOM-mode scan should also drop to 0 after
  this PR builds + releases).
- **Grype residuals** if Grype is added to CI in a future PR:
  - `CVE-2025-14847` on the 4.2.25 stepper -- documented residual,
    documented threat-model exception above.
  - `CVE-2017-2665` (HIGH) and `CVE-2014-8180` (MEDIUM) on both
    4.2.25 and 4.4.30 -- false positives (Red Hat Satellite 6
    skyring integration, not MongoDB Inc.).  Would need explicit
    suppression with citation.
- **Trivy `/usr/share/java/` gap**: the seven CVEs this prune
  closes were NOT surfaced by the Trivy image-mode scan that runs
  in CI (only by `trivy sbom` and Grype).  Hypothesis: the trivy-
  action's `vuln-type: os,library` filter combined with `ignore-
  unfixed: true` somehow excludes them at image-scan time.  Not
  investigated further in this PR.  Worth a follow-on PR to align
  CI's image-mode scan with the SBOM-mode scan (or to switch to
  SBOM-mode entirely).

### Why two scanners (foreshadowing)

This CVE escaping Trivy + appearing only via Grype is the
motivation for an eventual two-scanner CI setup
(Trivy for Java + apt packages where it excels; Grype for binary-
catalogued artifacts where syft's binary scanners produce
`pkg:generic/*` entries).  Not implemented in this PR -- staying
scoped to the CVE-driven version bump that justifies the release
on its own.

If a follow-on PR adds Grype to CI, the `.trivyignore`-equivalent
for the three Grype-only findings above (the 4.2.25 stepper
residual + two false positives) becomes part of that work.

### Sequencing note

This release assumes `v3.10.13-22` (the Phase 6 Guava reachability
audit + suppressions) ships first.  If the v3.10.13-22 PR is held,
this entry should be renumbered to v3.10.13-22 before merge.  Both
PRs are independent in their changes; the only coupling is the
CHANGELOG version numbering and the cross-references in this entry
to v3.10.13-22.

---

## [v3.10.13-21] -- complete the GHCR manifest-annotations fix that v3.10.13-20 started

### TL;DR

Hotfix.  v3.10.13-20 attempted to fix the missing GHCR package
description by adding an `annotations: ${{ steps.meta.outputs.annotations }}`
wire-up in `release.yml`'s `docker/build-push-action` step, but the
fix was incomplete in two ways:

1. **`metadata-action` does NOT derive annotations from the `labels:`
   input.**  Empirically verified in the v3.10.13-20 release log: the
   `--label org.opencontainers.image.title=UniFi Video Controller
   (modernized)` flag was emitted correctly, but the parallel
   `--annotation manifest:org.opencontainers.image.title=unifi-video-controller`
   used the auto-generated GitHub repo name, NOT the custom label.
   Same for description (`Docker for Unifi-Video Controller (Ubiquiti
   Networks)` -- the GitHub repo description -- instead of our UV
   3.10.13 line).  Fix: add an explicit `annotations:` input to
   `metadata-action` that mirrors the `labels:` block.

2. **Annotations defaulted to manifest level only, not index level.**
   GHCR's package UI reads from the multi-arch image index for images
   with provenance + SBOM attestations (which we ship).  v3.10.13-20's
   manifest had annotations, but the **index** had none.  Fix: set
   `DOCKER_METADATA_ANNOTATIONS_LEVELS=manifest,index` env var on the
   `metadata-action` step.

**Zero behavioural change.**  Container image bytes are identical to
v3.10.13-20 (the Dockerfile is unchanged); only the OCI manifest /
index annotations differ.  Trivy alert count: unchanged at **3**.

### Why this is a separate release

`release.yml` reads itself from the tagged commit (`actions/checkout`
with `ref: ${{ steps.tag.outputs.name }}`), so re-running the workflow
against the existing `v3.10.13-20` tag would still execute the
broken workflow.  The fix has to land on a new commit and ship as a
new tag.  v3.10.13-21 is the next patch level per the established
versioning pattern (`v3.10.13-N`).

### Changed

- **`.github/workflows/release.yml`**:
  - Adds an explicit `annotations:` input to `docker/metadata-action`
    that mirrors the `labels:` block for the eight `org.opencontainers.image.*`
    keys (title, description, version, source, url, documentation,
    licenses, vendor).
  - Adds `env: DOCKER_METADATA_ANNOTATIONS_LEVELS: manifest,index`
    on the `metadata-action` step so annotations propagate to both
    the per-platform manifest and the multi-arch index.
  - Comment block above the step now spells out the two-input gotcha
    (`labels` and `annotations` are independent) plus the
    manifest-vs-index level distinction, so the next maintainer
    doesn't burn an hour re-deriving this empirically.

### Verification (post-release)

After this PR merges and `v3.10.13-21` is tagged + the release
workflow runs, the manifest list at
`ghcr.io/conmilo/unifi-video-controller:v3.10.13-21` should carry:

```text
$ curl -sL \
    -H "Accept: application/vnd.oci.image.index.v1+json" \
    -H "Authorization: Bearer $(curl -s \
        'https://ghcr.io/token?scope=repository:conmilo/unifi-video-controller:pull' \
        | jq -r .token)" \
    'https://ghcr.io/v2/conmilo/unifi-video-controller/manifests/v3.10.13-21' \
    | jq '.annotations'
{
  "org.opencontainers.image.title": "UniFi Video Controller (modernized)",
  "org.opencontainers.image.description": "UniFi Video 3.10.13 on Ubuntu 24.04 + OpenJDK 21 LTS + MongoDB 4.4 (with 4.0->4.2->4.4 fCV migration); airvision identifier rewrite + Tomcat 9 Bootstrap shim applied at runtime by uv-patcher",
  ...
}
```

GHCR package page at
<https://github.com/conmilo/unifi-video-controller/pkgs/container/unifi-video-controller>
should render the description string verbatim.

## [v3.10.13-20] -- strip MongoDB 4.2 dist to mongod-only + parameterise MongoDB versions

### TL;DR

Image-size trim + minor Dockerfile ergonomics.  Adds a dedicated
`mongo42-extractor` build stage that strips the upstream MongoDB
4.2.25 tarball down to `bin/mongod` plus the SSPL-required
LICENSE / MPL-2 / THIRD-PARTY-NOTICES; the runtime stage copies the
pre-stripped tree in via `COPY --from=mongo42-extractor` instead of
extracting the full 302 MB distribution.  Final `/opt/mongodb-4.2/`
shrinks from 302 MB (13 binaries) to **71 MB** (`mongod` only).
Image total (compressed) drops from 1001.5 MB to **789.8 MB** --
**~212 MB saved**.

Also turns the two MongoDB tarball versions into build-time `ARG`s
(`MONGO44_VERSION=4.4.29`, `MONGO42_VERSION=4.2.25`) so future point
releases only require a checksum + ARG default bump.

**Zero behavioural change**.  `migrate-mongo.sh`, `run.sh`,
`uv-patcher`, runtime data layout, the JRE, and the default MongoDB
versions are all unchanged.  Trivy alert count: unchanged at **3**
(Guava 14 only -- Phase 6 work).

### Why this is a separate release rather than rolled into Phase 5/6

The strip is a layer-hygiene improvement orthogonal to CVE hardening;
landing it independently keeps the diff reviewable.  The ARG
parameterisation enables future maintainer-driven point-release
bumps (next likely value: `MONGO44_VERSION=4.4.30` if/when upstream
publishes a 4.4 patch release) without re-litigating the strip.

### What changed at the artifact level

| Path | Before | After | Notes |
|---|---|---|---|
| `/opt/mongodb-4.2/bin/` | 13 binaries, ~302 MB | `mongod` only, ~74 MB | `migrate-mongo.sh` only invokes `/opt/mongodb-4.2/bin/mongod` |
| `/opt/mongodb-4.2/` (dir) | dist tree, 302 MB | LICENSE + MPL-2 + THIRD-PARTY-NOTICES + bin/mongod, 71 MB | SSPL-required files retained |
| `Dockerfile` MongoDB pins | hard-coded `4.4.29` and `4.2.25` literals | `ARG MONGO44_VERSION=4.4.29` / `ARG MONGO42_VERSION=4.2.25` (overridable) | SHA256SUMS still tied to the default values |
| Total image (compressed) | 1001.5 MB | 789.8 MB | ~212 MB saving |

### Removed from `/opt/mongodb-4.2/`

Twelve unused binaries: `mongo`, `mongos`, `mongodump`, `mongorestore`,
`mongoexport`, `mongoimport`, `mongofiles`, `mongostat`, `mongotop`,
`mongoreplay`, `bsondump`, `install_compass`.

Two unused doc files: `README` (informational only) and
`THIRD-PARTY-NOTICES.gotools` (Go-tools attribution; we no longer
ship the 12 Go-built binaries it covers).

### Why the strip is safe

`migrate-mongo.sh` references `/opt/mongodb-4.2/bin/mongod` exactly
once (the `MONGOD_42` variable, used only for the 4.0 -> 4.2 fCV
step).  Verified there are no other references in the image:

```
$ grep -rn 'mongodb-4\.2/bin/[^m]' /root/unifi-video-controller
(no matches)

$ grep -rn '/opt/mongodb-4\.2/' /root/unifi-video-controller \
       | grep -v '/bin/mongod'
(no matches)
```

`ldd /opt/mongodb-4.2/bin/mongod` shows no shared lib lives inside
`/opt/mongodb-4.2/` itself (everything resolves to OS libs +
`/usr/local/lib/libssl.so.1.1` from the libssl11-source stage), so
deleting siblings cannot break mongod.

Empirically verified in an existing `uv-test:p7-current` image by
deleting the unused binaries in-place and replaying every operation
`migrate-mongo.sh` performs against mongod 4.2:

- `start_mongo` (`--fork --dbpath ... --port 27999`)
- `wait_mongo` (ping in a loop)
- `get_fcv` (`db.adminCommand({getParameter:1, featureCompatibilityVersion:1})`)
- `set_fcv` (`db.adminCommand({setFeatureCompatibilityVersion:"4.2"})`)
- `stop_mongo` (`db.adminCommand({shutdown:1})`)

All succeed cleanly; mongod log ends with `shutting down with code:0`.

### Why 4.4 stays the major-version ceiling

Two independent constraints, both hard blockers for MongoDB 5.0+:

1. **Driver wire protocol**.  `airvision.jar` bundles
   `mongo-java-driver-2.14.2.jar` (per `docs/JAR-INVENTORY.md` line
   474) and imports the legacy `com.mongodb.{DB,DBCollection,DBCursor,
   DBObject,BasicDBObject}` API.  The 2.x Java driver predates the
   `OP_MSG` unified wire opcode (added in the 3.6 driver line, late
   2017).  MongoDB 5.0 **removed** support for the legacy write
   opcodes the 2.x driver uses, so UV writes against MongoDB 5.0+
   fail at the wire-protocol level.  Unblocking this is Phase 5/6
   work per `docs/PHASE-2-ROADMAP.md`: ~45 airvision source files
   would need ASM bytecode rewriting from legacy `DB*` API to modern
   `mongodb-driver-sync` types, which Ubiquiti does not ship sources
   for.

2. **AVX requirement**.  MongoDB 5.0+ uses AVX instructions in its
   storage engine.  The Apollo Lake reference target (Celeron J3455
   / Goldmont microarchitecture) lacks AVX, so 5.0+ binaries
   `SIGILL` at startup.

The new ARGs cannot accidentally escape this ceiling:
`checksums/SHA256SUMS` only lists 4.4.x / 4.2.x point-release
entries, so attempting `--build-arg MONGO44_VERSION=5.0.0` hits
either a 404 on `fastdl.mongodb.org` or a missing-checksum failure
in `sha256sum -c`.  There is no path to a silently-broken
UV-on-MongoDB-5 image.

### Why 4.2 stays in the image (briefly)

MongoDB requires walking through every major version's
`setFeatureCompatibilityVersion` during the 4.0 -> 4.4 upgrade --
this is the only **officially supported** path.  See the MongoDB
staff reply at
<https://www.mongodb.com/community/forums/t/upgrading-from-4-0-to-4-4-using-mongodump/143015/2>
which describes `mongodump`/`mongorestore` as the "skip the steps"
alternative but flags it as not officially supported (collection /
index / oplog formats can change between majors).
`migrate-mongo.sh` follows the supported in-place fCV walk, which
requires the 4.2 `mongod` binary to actually be present in the
image.  Only **mongod** is needed -- the other 12 4.2 binaries are
never invoked, which is why the strip is safe.

### How to bump MongoDB point releases going forward

1. Regenerate the SHA256 line in `checksums/SHA256SUMS` for the new
   tarball:

   ```
   wget -q https://fastdl.mongodb.org/linux/mongodb-linux-x86_64-ubuntu2004-4.4.30.tgz
   sha256sum mongodb-linux-x86_64-ubuntu2004-4.4.30.tgz
   ```

2. Update the `MONGO44_VERSION` (or `MONGO42_VERSION`) default in
   `Dockerfile`'s global ARG block.

3. Rebuild; the fetcher's `sha256sum -c` validates the pin.

A user who wants to test a point-release locally without modifying
the repo can:

```bash
docker buildx build \
    --build-arg MONGO44_VERSION=4.4.30 \
    -t local:mongo44-bump-test .
```

...but must also pass an updated SHA256SUMS (or temporarily relax the
fetcher's checksum verification) since the default SHA256SUMS still
targets the committed version.  This is the intended failure mode for
an unverified binary.

### Files NOT changed (intentionally)

- `migrate-mongo.sh` -- binary paths are identical.
- `run.sh`.
- `checksums/SHA256SUMS` -- default ARG values target the
  byte-identical upstream tarballs.
- `docs/MIGRATION.md` -- user-facing flow is identical.
- `uv-patcher/**`.

### Changed

- **`Dockerfile`**:
  - Header comment (lines ~12-21) -- MongoDB summary now spells out
    both upgrade-blockers (driver pin + AVX) and notes the two new
    ARG tunables.
  - Global ARG block (after `AL2_DIGEST`) -- adds `MONGO44_VERSION`
    and `MONGO42_VERSION` defaults with a comment block explaining
    their relationship to `checksums/SHA256SUMS` and the 4.4 ceiling.
  - `fetcher` stage -- re-declares both ARGs and substitutes them
    into the two MongoDB `wget` URLs (no change to checksum
    verification semantics).
  - New `mongo42-extractor` stage between `libssl11-source` and the
    runtime stage -- untars the 4.2 tarball, deletes every `bin/`
    entry except `mongod`, deletes the `README` and
    `THIRD-PARTY-NOTICES.gotools` files (retains LICENSE-Community.txt,
    MPL-2, THIRD-PARTY-NOTICES), smoke-tests `test -x mongod`.
  - libssl1.1 comment block (now lines ~346-359) -- updated to
    spell out **both** upgrade-blockers (driver + AVX) instead of
    only the AVX one.
  - Runtime stage MongoDB install block -- replaces the
    `COPY ...mongo42.tgz` + `tar -xzf` for 4.2 with a single
    `COPY --from=mongo42-extractor /opt/mongodb-4.2 /opt/mongodb-4.2`;
    substitutes `${MONGO44_VERSION}` into the remaining 4.4 paths.
    The `mongod-4.2` symlink and the smoke `mongod-4.2 --version`
    line are unchanged.
- **`mongodb-server-equivs.control`** -- long description tweaked
  from "4.2.25 server alongside at /opt/mongodb-4.2/" to "4.2.x server
  (mongod-only, ~71 MB stripped via the mongo42-extractor stage)
  alongside at /opt/mongodb-4.2/".  Cosmetic; the equivs stub itself
  is unchanged.
- **`CHANGELOG.md`** -- this entry; plus editorial pass on the three
  historical "AVX-only" rationale lines (v3.10.13-16 commit-a, v3.10.13-16
  residuals list, v3.10.13-1 libssl1.1 entry) to prepend the driver
  constraint so historical and current narrative agree.
- **`.github/workflows/release.yml`** -- adds the
  `org.opencontainers.image.description` (and `source` / `url` /
  `documentation` / `licenses` / `vendor`) to `docker/metadata-action`'s
  `labels:` block, AND wires `annotations: ${{ steps.meta.outputs.annotations }}`
  into `docker/build-push-action` so the manifest carries OCI
  annotations.  GHCR reads the package description from manifest
  annotations rather than image-config labels, so prior releases
  showed no description on the package page even though the
  `Dockerfile` `LABEL org.opencontainers.image.description=...` was
  set correctly.  v3.10.13-20 is the first release to render a
  description on GHCR.

### Residuals (unchanged from v3.10.13-19)

- 3 alerts for Guava 14.0.1 -- still deferred to Phase 6 per
  `docs/PHASE-5-ROADMAP.md` Phase 6 section.

Expected post-release alert count: **3** (unchanged from v3.10.13-19;
this release does not affect CVE surface).

### Verification (locally, x86_64 host)

- **hadolint** (`--failure-threshold warning`, matching CI): clean.
- **`docker buildx build --load`**: succeeds.
- **/opt/mongodb-4.2/ listing inside the built image**:

  ```text
  $ docker run --rm local:mongo42-strip-test ls /opt/mongodb-4.2/bin/
  mongod

  $ docker run --rm local:mongo42-strip-test ls /opt/mongodb-4.2/
  LICENSE-Community.txt
  MPL-2
  THIRD-PARTY-NOTICES
  bin

  $ docker run --rm local:mongo42-strip-test du -sh /opt/mongodb-4.2
  71M    /opt/mongodb-4.2
  ```

- **mongod 4.2 version smoke** (resolves libcurl/libssl correctly at
  runtime via the apt-installed + libssl11-source-COPYed libs):

  ```text
  $ docker run --rm local:mongo42-strip-test /opt/mongodb-4.2/bin/mongod --version
  db version v4.2.25
  git version: 41b59c2bfb5121e66f18cc3ef40055a1b5fb6c2e
  OpenSSL version: OpenSSL 1.1.1zg  7 Apr 2026
  allocator: tcmalloc
  modules: none
  build environment:
      distmod: ubuntu1804
      distarch: x86_64
      target_arch: x86_64

  $ docker run --rm local:mongo42-strip-test mongod-4.2 --version
  db version v4.2.25
  (...same...)
  ```

- **Image size delta**:

  ```text
  $ docker image inspect uv-test:p7-current     --format '{{.Size}}'
  1050100843   # ~1001.5 MB

  $ docker image inspect local:mongo42-strip-test --format '{{.Size}}'
  828166429    #  ~789.8 MB
  ```

  Saving: ~212 MB compressed (vs. the on-disk 231 MB for `/opt/mongodb-4.2/`
  itself; the delta between 231 MB on-disk and 212 MB compressed is
  Docker layer compression).

- **5-operation `migrate-mongo.sh` smoke**: covered by the previous
  pre-flight verification against `uv-test:p7-current` per the
  "Why the strip is safe" section above; not re-run since the image
  contents are byte-identical to the pre-flight smoke target.

### Not yet verified (post-merge maintainer smoke)

- **Live-data fCV walk** against the production NAS dataset (4.0 ->
  4.2 -> 4.4 step) -- the local pre-flight verified all 5 mongod
  operations against the stripped 4.2 binary, but the live walk is
  the integration test of record.
- **24h soak** -- same.

If either surfaces an issue, expect a `v3.10.13-20.1` follow-up.
Rollback target: revert the COPY-from-mongo42-extractor line to the
in-runtime tar -- restores the full 4.2 dist at the cost of the
212 MB.

### Commits (3 total)

| # | Commit | Summary |
|---|---|---|
| a | `build: strip MongoDB 4.2 dist to mongod-only + parameterise MongoDB versions` | the load-bearing functional change -- new mongo42-extractor stage + ARG parameterisation + dual-constraint comment block updates + equivs.control long-description tweak |
| b | `docs: CHANGELOG v3.10.13-20 entry + dual-constraint editorial pass on historical AVX rationale` | this entry + the three historical-line clarifications |
| c | `ci(release): publish OCI manifest annotations so GHCR shows the package description` | release.yml metadata-action labels expansion + build-push-action annotations wiring; CHANGELOG entry note |

## [v3.10.13-19] -- Phase 5: BouncyCastle 1.60 -> 1.84 (jdk15on -> jdk18on)

### TL;DR

Replace the .deb-bundled BouncyCastle 1.60 (`jdk15on` family) with
BouncyCastle 1.84 (`jdk18on` family) from Maven Central, SHA256-pinned.
Closes 10 Trivy alerts (7 distinct CVEs).  Expected post-release Trivy
alert count: **3** (Guava 14 only; Phase 6 work).

No functional behaviour change.  Zero changes to `run.sh`,
`migrate-mongo.sh`, `uv-patcher` Java code (the spec JSON gains new
entries; no rewriter logic changes), runtime data layout, MongoDB
versions, or the JRE.

### What changed at the artifact level

| .deb-installed 1.60 | Image-installed 1.84 | Disposition |
|---|---|---|
| `bcprov-jdk15on-160.jar` | `bcprov-jdk18on-1.84.jar` | **Swap** (filename rename + version bump) |
| `bcpkix-jdk15on-160.jar` | `bcpkix-jdk18on-1.84.jar` | **Swap** |
| `bcprov-ext-jdk15on-160.jar` | (no replacement) | **Retire** -- discontinued upstream; not needed |
| `bctls-jdk15on-160.jar` | (no replacement) | **Retire** -- never instantiated by airvision |
| (none) | `bcutil-jdk18on-1.84.jar` | **Add** -- new transitive dep |

The four 1.60 jars all ship in the upstream `unifi-video.deb` and are
all referenced in `airvision.jar`'s `META-INF/MANIFEST.MF` Class-Path.
After this release:

- The three 1.84 jars get `install`'d into `/usr/lib/unifi-video/lib/`
  by the Dockerfile, alongside the existing modernized JARs from earlier
  phases (log4j, jackson, tomcat, etc.).
- The four 1.60 jars get `rm`'d from `/usr/lib/unifi-video/lib/`
  immediately after.
- `uv-patcher`, at container start, rewrites `airvision.jar`'s
  Class-Path:
  - `bcprov-jdk15on-160.jar` -> `bcprov-jdk18on-1.84.jar`
  - `bcpkix-jdk15on-160.jar` -> `bcpkix-jdk18on-1.84.jar`
  - `bcprov-ext-jdk15on-160.jar` -> empty (token removed from list)
  - `bctls-jdk15on-160.jar` -> empty (token removed from list)
  - `bcutil-jdk18on-1.84.jar` appended (via `jarFilenameAdditions`)

### Why 1.84 and not the Phase 5 roadmap's 1.78.1

`docs/PHASE-5-ROADMAP.md` (written 2026-05-24) targeted 1.78.1 because
that was the current latest at write time.  By 2026-05-25 (the day this
PR opened), Maven Central had advanced through 1.79, 1.80, 1.80.2,
1.81, 1.81.1, 1.82, 1.83, and 1.84 (released 2026-05-15).  Bumping
straight to 1.84 closes everything 1.78.1 would close plus all
subsequent CVE-fix releases; the cost is identical (same `wget` +
same `install -m 400` + same patcher spec).

AWS SDK v2 and Apache Tomcat 11 GA both consume `bcprov-jdk18on-1.84`,
which gives a Tier-1 vendor corroboration on the trust profile.

### Why retire `bcprov-ext` and `bctls` instead of bumping them

**`bcprov-ext-jdk18on`**: upstream discontinued the artifact at 1.78.1
(the 1.78.1 JAR is even a 404 on Maven Central -- the metadata lists
it but the .jar file was never published; the last actually-available
release is 1.78).  No 1.79 or later `bcprov-ext-jdk18on` exists.  The
contents of `bcprov-ext` (additional EC curves like GOST, less-common
Camellia/SEED variants) aren't used by airvision's BC surface (4
flows total: self-signed cert generation, PEM read/write, PKCS#10 CSR,
provider registration -- all use standard NIST P-256/P-384 or RSA which
live in main `bcprov`).  Bumping to 1.78 just for `bcprov-ext` while
the other artifacts go to 1.84 would create a mixed-version classpath
which is asking for `LinkageError` between BC components.

**`bctls-jdk15on-160.jar`**: BC's JSSE provider.  The Phase 5 roadmap
(`docs/PHASE-5-ROADMAP.md` line 109-115) verified that airvision's
`service/security/*` package init calls
`Security.addProvider(new BouncyCastleProvider())` and **never**
`Security.addProvider(new BouncyCastleJsseProvider())`.  After Phase 3.4
(v3.10.13-14) rewrote the :7442 connector cipher list, both `:7443`
and `:7442` use JDK 21's native JSSE; BC's JSSE classes were dead
weight.  Keeping `bctls-1.60` on the classpath alongside `bcprov-1.84`
would risk `LinkageError` if any code path ever instantiated a bctls
class (the version skew between bctls's expectations of the bcprov API
and bcprov's actual 1.84 API is significant -- multiple methods bctls
calls were removed/renamed between 1.60 and 1.84).  Removing the file
+ stripping the manifest reference is the safer move.

### Why add `bcutil-jdk18on-1.84.jar`

BouncyCastle 1.71 (2022) reorganised its shared utility classes into a
separate Maven artifact (`bcutil-jdk18on`).  Previously these classes
lived inside `bcprov-jdk15on`.  The `bcpkix-jdk18on-1.84.pom` declares
a hard dependency on `bcutil-jdk18on-1.84`.  Without it on
`airvision.jar`'s Class-Path:

- All four BC code paths still load class-by-class on demand
- airvision boot succeeds (the BC provider registration only needs
  `bcprov` classes which are on the path)
- The first call to `X509v3CertificateBuilder` (self-signed cert
  generation) or `PKCS10CertificationRequestBuilder` (CSR generation)
  throws `NoClassDefFoundError: org/bouncycastle/util/...` when bcpkix
  tries to resolve its bcutil dependencies

So `bcutil-jdk18on-1.84.jar` is added to `jarFilenameAdditions` in
`airvision-renames.json` (same mechanism Phase 3.2 used to add the
JAXB + JAF runtime JARs that Java 11+ no longer ships in the JDK).

### CVEs closed (7 distinct, 10 Trivy alerts)

Per `docs/PHASE-5-ROADMAP.md` Phase 5 CVE inventory:

- **CVE-2026-5588** (medium) -- `bcpkix-jdk15on` PKIX `CompositeVerifier`
  accepts empty signature sequence as valid
- **CVE-2025-8916** (medium) -- `bcpkix-jdk15on` DoS in some PKIX path
- **CVE-2024-30171** (medium) -- `bcprov-jdk15on` timing variant of
  Bleichenbacher (Marvin attack)
- **CVE-2024-29857** (medium) -- `bcprov-jdk15on` EC F2m parameters DoS
  in certificate import
- **CVE-2023-33202** (medium) -- `bcprov-jdk15on` + `bcprov-ext-jdk15on`
  OOM via crafted ASN.1 in `PEMParser`
- **CVE-2020-26939** (medium) -- `bcprov-jdk15on` + `bcprov-ext-jdk15on`
  side-channel on RSA decryption with `OAEPPadding`
- **CVE-2020-15522** (medium) -- `bcprov-jdk15on` + `bcprov-ext-jdk15on`
  timing issue within EC math library

All 7 are fixed in BC 1.78+; 1.84 carries them all.

### Residuals (unchanged from v3.10.13-18)

- 3 alerts for Guava 14.0.1 -- still deferred to Phase 6 per
  `docs/PHASE-5-ROADMAP.md` Phase 6 section.

Expected post-release alert count: **3** (was 13 after v3.10.13-18,
was 25 after v3.10.13-17, was 79 at the start of Phase 4).

### Changed

- **`Dockerfile`** -- fetcher stage adds three `wget` lines for the
  new BC jars (`bcprov-jdk18on-1.84.jar`, `bcpkix-jdk18on-1.84.jar`,
  `bcutil-jdk18on-1.84.jar`).  Runtime stage's "Phase 3 bundled JAR
  refresh" block adds three `install -m 400` lines and four `rm`
  entries for the legacy 1.60 filenames.  A new ~40-line comment
  block above the COPY-lines documents the artifact-set choice
  (3 in, 4 out) with rationale for each disposition.
- **`checksums/SHA256SUMS`** -- adds three SHA256 lines for the
  Maven Central BC 1.84 jars.
- **`uv-patcher/src/main/resources/airvision-renames.json`**:
  - `jarFilenameRenames`: 4 new entries (2 jdk15on->jdk18on renames,
    2 jdk15on->empty removals)
  - `jarFilenameAdditions`: 1 new entry (`bcutil-jdk18on-1.84.jar`)
  - `_jarFilenameAdditions_comment`: extended with Phase 5 rationale
    for `bcutil`
  - `_changelog`: new v4 entry summarising the Phase 5 rewrites
- **`uv-patcher/src/test/java/.../RenameSpecTest.java`** -- bumps the
  `jarFilenameAdditions` count assertion from 7 to 8, adds 4 BC rename
  assertions + 1 bcutil-in-additions assertion.
- **`README.md`** -- "What's modernized" table gains a BouncyCastle
  row matching the format of the existing libssl1.1 / log4j rows.
- (no uv-patcher Java code changes -- the existing rename-with-empty-
  replacement + Class-Path-additions mechanisms already cover the
  Phase 5 spec entries cleanly.)

### Verification

- `mvn -B test` in `uv-patcher/`: **37/37 pass** (new BC entries
  asserted; pre-existing tests unchanged).
- `docker build --no-cache --platform linux/amd64`: succeeds.  Image
  size unchanged within rounding (3 jars in, 4 jars out at similar
  per-jar sizes).
- Inside the built image:
  ```text
  $ docker run --rm <image> ls /usr/lib/unifi-video/lib/ | grep ^bc
    bcpkix-jdk18on-1.84.jar
    bcprov-jdk18on-1.84.jar
    bcutil-jdk18on-1.84.jar
  $ docker run --rm <image> ls /usr/lib/unifi-video/lib/ | grep 'jdk15on-160'
    (no output -- all four legacy jars correctly absent)
  ```
- Container start against a fresh ephemeral data dir reaches
  `(healthy)` in ~90 seconds.  4-endpoint probe returns expected HTTP
  codes (`/` 200, `/api/2.0/bootstrap` 200, `/api/2.0/login` 400
  for bogus creds with no admin user yet, `/api/2.0/server` 200).
- `openssl s_client -connect <container>:7443 -tls1_2` succeeds with
  TLSv1.2 + `ECDHE-RSA-AES256-GCM-SHA384` + self-signed cert
  (CN=UniFi-Video Controller, UID=<install-unique-uuid>) -- this
  proves BC 1.84's `X509v3CertificateBuilder` + `JcaContentSignerBuilder`
  generated the controller's self-signed cert successfully, which is
  the highest-risk airvision BC code path.
- `openssl s_client -connect <container>:7442 -tls1_2` from inside
  the container succeeds with the same cert + cipher (Phase 3.4 cipher
  regression check passes).
- Patched `airvision.jar` MANIFEST.MF Class-Path references
  `bcprov-jdk18on-1.84.jar`, `bcpkix-jdk18on-1.84.jar`,
  `bcutil-jdk18on-1.84.jar` and does NOT reference any `jdk15on-160`
  filename.
- `uv-patcher` boot log: "discovered 174 class renames, 1608 member
  renames" + "airvision rewrite complete: 990 classes processed, 174
  class entries renamed, 1608 method/field references renamed, 2
  Bootstrap.setCatalina* call(s) rewritten" -- same counts as
  v3.10.13-18, confirming no incidental rewriting changes.
- `grep -cE '[ERROR]' /var/log/unifi-video/server.log` returns 1 -- a
  single pre-existing "Error enabling JCE strong security" line from
  airvision's Java-8-era reflective JCE-unlimited hack that doesn't
  work on Java 11+ but is benign because Java 9+ ships JCE unlimited
  as the default.  Identical line + count appears in the v3.10.13-18
  fresh-data smoke; not a Phase 5 regression.

### Not yet verified (carved out for the daytime smoke)

Per the PR opener's request, the following exercises require physical
NAS access + camera fleet, so they're explicitly out of scope for the
PR's automated smoke and will be run by the maintainer post-merge
against the live NAS:

1. **Camera adoption** (G3 + G4) -- exercises BC's PKCS#10 CSR
   exchange + self-signed-cert verification on a freshly-adopted
   camera's response.
2. **24h soak** -- catches any subtle PKIX validation regression in
   OCSP/CRL paths or in long-lived TLS sessions between the controller
   and cameras.

If either of these surfaces an issue, expect a v3.10.13-19.1 follow-up
PR with the specific regression fix (or, worst case, a roll-back to
1.78 which is the last release where `bcprov-ext` exists and would
restore the full original artifact set).

## [v3.10.13-18] -- Phase 7: libssl1.1 from Amazon Linux 2 openssl11 1.1.1zg

### TL;DR

Replace the focal-security `libssl1.1_1.1.1f-1ubuntu2.24` deb (whose
post-1.1.1f-1ubuntu2.24 CVE backports moved to the paywalled Ubuntu
Pro ESM channel) with Amazon Linux 2's `openssl11-libs-1.1.1zg-1.amzn2.0.1`
RPM, extracted in a new `libssl11-source` Dockerfile build stage and
copied bare into `/usr/local/lib/`.  Closes the 12 remaining libssl1.1
CVE alerts.

Expected post-release Trivy alert count: **13** (down from 25 after
v3.10.13-17, and from 79 at the start of Phase 4).  Remaining residual:
BouncyCastle + Guava (Phase 5/6 per `docs/PHASE-5-ROADMAP.md`).

No functional behaviour change.  Zero changes to `run.sh`,
`migrate-mongo.sh`, `uv-patcher` logic, runtime data layout, MongoDB
versions, or the JRE.  The runtime image still ships `libssl.so.1.1` +
`libcrypto.so.1.1` for MongoDB 4.4's dynamic loader; the source of
those binaries has changed.

### Why Amazon Linux 2 openssl11 and not the obvious alternatives

The 12 residuals at the end of Phase 4 followup were CVEs disclosed
**after** OpenSSL 1.1.1 went upstream EOL (2023-09-11), with Trivy
citing fixes in `openssl 3.0.13-0ubuntu3.6+` -- the OpenSSL 3.x package
that lives next to (not inside) the focal libssl1.1 1.1.1f line.
Canonical did backport these CVEs for paying Ubuntu Pro ESM customers
but never released the patched 1.1.1f-1ubuntu2.25+ to the public
focal-security pool.  So the legacy 1.1.1f-1ubuntu2.24 deb we shipped
genuinely carries the CVEs Trivy was citing.

Candidates evaluated, in order of trust:

1. **Amazon Linux 2 `openssl11`** *(chosen)*
   - Amazon's Linux security team maintains `openssl11` with CVE
     backports published per release as ALAS advisories (the 1.1.1zg
     release maps to ALAS2-2026-3249, closing CVE-2026-28387..-28390;
     earlier z* releases close the rest of our 12).
   - The exact `openssl11-libs-1.1.1zg-1.amzn2.0.1.x86_64.rpm` is the
     same binary AWS CLI v2 itself bundles in release 2.34.32 (see
     aws/aws-cli@6b97442 + PR #10225) -- verified by extracting
     `_ssl.cpython-*.so` from the CLI zip and reading the embedded
     "OpenSSL 1.1.1zg 7 Apr 2026" version string.
   - RPM signed by Amazon Linux's release key
     (`RPM-GPG-KEY-amazon-linux-2`, Key ID `11cf1f95c87f5b1a`),
     verified automatically by `yum install` against
     `/etc/pki/rpm-gpg/RPM-GPG-KEY-amazon-linux-2` baked into the
     official `public.ecr.aws/amazonlinux/amazonlinux:2` image.
   - Source RPM `openssl11-1.1.1zg-1.amzn2.0.1.src.rpm` publicly
     downloadable from Amazon's AL2 repos; per-CVE patches in the
     spec file are cross-referenced with Red Hat and MITRE entries
     for audit.

2. **`kzalewski/openssl-1.1.1` GitHub fork** *(rejected)*
   - Single-maintainer community repo.  Version naming
     (1.1.1z, za, zb, ... zg) mirrors the alphabetical-extension
     convention reportedly used by OpenSSL Foundation's paid
     Premium Support, raising legal-optics concerns.
   - Patches themselves trace back to public sources (openEuler
     LTS branch + cherry-picks from upstream OpenSSL 3.x devs
     Matt Caswell, Tomas Mraz, Neil Horman, Bob Beck, Viktor
     Dukhovni) so the code is OK, but the naming + single-SPOF
     risk profile is worse than Amazon's.

3. **AlmaLinux `compat-openssl11` SRPM** *(rejected)*
   - Distro-vendor trust comparable to AL2, but requires building
     from SRPM (more pipeline complexity than `yum install` on AL2).
   - Different vendor than AWS CLI's source, so we'd lose the
     "same binary as a Tier-1 vendor's product" corroboration.

4. **OpenSSL Foundation Premium Support subscription** *(rejected)*
   - Paid, and the license restricts public redistribution of the
     patched source.  Doesn't fit an MIT-licensed open-source fork.

5. **Self-compile from upstream 1.1.1w + hand-port CVE patches** *(rejected)*
   - All 12 CVEs are post-1.1.1w EOL.  Compiling 1.1.1w upstream
     closes zero of them.  We'd be reimplementing what Amazon
     already does.

6. **Skip the bump, accept the residuals** *(was the v3.10.13-17 status quo)*
   - Defensible (these CVEs are in OpenSSL code paths
     MongoDB 4.4's fCV migration doesn't exercise, and mongod
     binds only to 127.0.0.1:7441 during that brief migration),
     but the Security tab visibility is worse and the
     defensibility burden falls on each operator.

### Changed

- **`Dockerfile`** -- new `ARG AL2_DIGEST` immediately after the
  existing `UBUNTU_DIGEST` ARG.  New build stage `libssl11-source`
  based on `public.ecr.aws/amazonlinux/amazonlinux:2@${AL2_DIGEST}`
  that runs `yum install -y openssl11-libs-${OPENSSL11_LIBS_VERSION}`
  (default `1.1.1zg-1.amzn2.0.1`) and writes the package version into
  a marker file `/openssl11-libs-version.txt`.  The runtime stage's
  legacy libssl1.1 install block (was `COPY` + `dpkg -i` of the
  focal-security deb) is replaced with three `COPY --from=libssl11-source`
  lines for `libssl.so.1.1.1zg`, `libcrypto.so.1.1.1zg`, and the
  version marker; followed by `ldconfig` + a self-check that the
  two .so files are findable via `ldconfig -p`.
- **`Dockerfile`** -- fetcher stage drops the legacy
  `wget -q http://security.ubuntu.com/.../libssl1.1_1.1.1f-1ubuntu2.24_amd64.deb`
  line.  No longer consumed anywhere downstream.
- **`Dockerfile`** -- adjacent stale comment near the Phase 4
  follow-up apt-get-upgrade block updated to reflect that the
  libssl1.1 + libcrypto.so.1.1 pair now comes from the
  digest-pinned AL2 stage instead of a SHA256-pinned Ubuntu deb.
- **`checksums/SHA256SUMS`** -- drops the
  `libssl1.1_1.1.1f-1ubuntu2.24_amd64.deb` SHA256 line.  The
  AL2 RPM is verified by `yum`'s built-in GPG signature check
  against Amazon's release key; no SHA256-pin needed at this
  layer (`AL2_DIGEST` pins the image bytes already).
- **`.trivyignore`** -- the 38 per-CVE libssl1.1 audit entries
  (16 not-affected + 22 released) are deleted.  Trivy's OS-package
  scanner has no `libssl1.1` dpkg entry to match against in the
  new image, so the entries became unreachable.  File kept (with
  a header explaining the Phase 7 transition) so future
  suppressions don't need to reconstruct the audit methodology
  from scratch.
- **`README.md`** -- "What's modernized" table libssl1.1 row
  updated; "Supply chain" row updated to mention the new AL2-stage
  pinning; JRE-history section's libssl1.1 reference updated;
  "Updating pinned artifacts" section explains the new
  `AL2_DIGEST` + `OPENSSL11_LIBS_VERSION` bump path.

### CVEs closed

All 12 of the v3.10.13-17 libssl1.1 residuals:

- **CVE-2025-9230** (medium) -- closed in 1.1.1zd batch
- **CVE-2025-68160** (low) -- closed in 1.1.1ze batch
- **CVE-2025-69418** (low) -- closed in 1.1.1ze batch
- **CVE-2025-69419** (low) -- closed in 1.1.1ze batch
- **CVE-2025-69420** (low) -- closed in 1.1.1ze batch
- **CVE-2025-69421** (low) -- closed in 1.1.1ze batch
- **CVE-2026-22795** (low) -- closed in 1.1.1ze batch
- **CVE-2026-22796** (low) -- closed in 1.1.1ze batch
- **CVE-2026-28387** (low) -- closed in 1.1.1zg batch (ALAS2-2026-3249)
- **CVE-2026-28388** (low) -- closed in 1.1.1zg batch (ALAS2-2026-3249)
- **CVE-2026-28389** (low) -- closed in 1.1.1zg batch (ALAS2-2026-3249)
- **CVE-2026-28390** (low) -- closed in 1.1.1zg batch (ALAS2-2026-3249)

### Residuals (unchanged from v3.10.13-17)

- 13 alerts for BouncyCastle 1.60 + Guava 14.0.1 -- still deferred
  to Phase 5 / Phase 6 per `docs/PHASE-5-ROADMAP.md`.

Expected post-release alert count: **13** (was 25 after v3.10.13-17).

### Verification

- `mvn -B test` in `uv-patcher/`: 37/37 pass (no patcher-code change).
- `docker build --no-cache --platform linux/amd64`: succeeds.  Image
  size unchanged within rounding (the AL2 .so files are similar size
  to the Canonical ones they replaced; the AL2 stage is build-time
  only and is not present in the final image).
- Inside the built image:
  ```
  $ docker run --rm <image> ldconfig -p | grep -E "lib(ssl|crypto)\.so\.1\.1"
    libssl.so.1.1 (libc6,x86-64) => /usr/local/lib/libssl.so.1.1
    libcrypto.so.1.1 (libc6,x86-64) => /usr/local/lib/libcrypto.so.1.1
  $ docker run --rm <image> strings /usr/local/lib/libssl.so.1.1 \
        | grep "^OpenSSL"
    OpenSSL 1.1.1zg  7 Apr 2026
  $ docker run --rm <image> ldd /opt/mongodb-4.4/bin/mongod \
        | grep -E "libssl|libcrypto"
    libcrypto.so.1.1 => /usr/local/lib/libcrypto.so.1.1
    libssl.so.1.1   => /usr/local/lib/libssl.so.1.1
    libssl.so.3     => /lib/x86_64-linux-gnu/libssl.so.3
    libcrypto.so.3  => /lib/x86_64-linux-gnu/libcrypto.so.3
  $ docker run --rm <image> dpkg-query -W libssl1.1
    dpkg-query: no packages found matching libssl1.1
  ```
  - mongod 4.4's dynamic loader resolves both 1.1 symlinks to
    `/usr/local/lib/` (our AL2-sourced files), not to any dpkg path
    (correctly absent).  The 3.x lines are mongod's separate link
    against Ubuntu's libssl3 -- unrelated to the swap.
- Container start against a fresh ephemeral data dir reaches
  `(healthy)` in ~90 seconds.  4-endpoint probe returns expected
  HTTP codes (`/` 200, `/api/2.0/bootstrap` 200,
  `/api/2.0/login` 400 for bogus creds with no admin user set
  up yet, `/api/2.0/server` 200).  Zero `[ERROR]` lines in
  `/var/log/unifi-video/server.log`.  `migrate-mongo` correctly
  identifies the fresh data dir and skips the fCV migration.

### Notes

The same caveat as v3.10.13-17 applies to the maintainer's
prod-data snapshot smoke: it requires the live NAS instance to be
quiesced so the WiredTiger log doesn't get caught mid-rotation in
a version mongod 4.4.29 can't read.  When the NAS is restarted for
the v3.10.13-18 upgrade rollout, the snapshot method will work
again for any future Phase 5 / 6 testing.

## [v3.10.13-17] -- Phase 4 follow-up: apt-get upgrade + jackson-core 2.21.3

### TL;DR

Two follow-up fixes for residuals that v3.10.13-16's release build
should have closed but didn't:

1. **`apt-get -y upgrade` in all three Dockerfile stages** -- closes
   14 OS-level alerts (13 libgnutls30t64 + 1 sed) that Phase 4
   predicted would auto-close on next monthly rebuild.
2. **jackson-core 2.19.0 -> 2.21.3** -- closes 2 jackson-core alerts
   that re-opened when the GHSA-72hv-8253-57qq advisory was
   re-scored to also cover the 2.19.0..<2.21.1 range.

Net effect: 16 fewer open alerts.  No functional behaviour change;
zero changes to run.sh, migrate-mongo.sh, uv-patcher logic, run-time
data layout, MongoDB versions, or the JRE.

### Why this is a separate release

Both issues were specific to assumptions made during the Phase 4
plan/audit that turned out wrong after the v3.10.13-16 release built
and Trivy re-scanned the image:

- The Phase 4 plan said "the 14 gnutls/sed alerts will auto-close
  on next monthly rebuild against current noble" -- on the assumption
  that `apt-get install -y X Y Z` would refresh transitive
  dependencies.  It doesn't; it only installs X, Y, Z.  Since
  libgnutls30t64 isn't in our explicit install list (it's a
  transitive of libcurl4), apt sees "already satisfied" and leaves
  the version from the base image's pre-baked package set untouched.
  Verified post-release: `docker run --rm <v3.10.13-16> dpkg-query
  -W libgnutls30t64` returned the old 3.8.3-1.1ubuntu3.5, not
  3.8.3-1.1ubuntu3.6 as Trivy's "Fixed Version" claimed.
- The Phase 4 plan picked jackson-core 2.19.0 based on
  search.maven.org's solrsearch API saying that was the latest 2.x.
  search.maven.org was stale; the authoritative
  https://repo1.maven.org/maven2/com/fasterxml/jackson/core/jackson-core/
  directory listing shows 2.19.x..2.21.x (latest 2.21.3).  And the
  GHSA was re-scored to cover 2.19.0..<2.21.1.  So the v3.10.13-16
  bump to 2.19.0 was both incomplete (advisory expanded) and
  unnecessarily conservative (newer versions available).

### Changed

- **`Dockerfile` fetcher stage (line 37)** -- `apt-get update` is
  followed by `apt-get -y upgrade` BEFORE the targeted
  `apt-get install --no-install-recommends`.  Pulls every pending
  noble-security update for already-installed packages from the
  Canonical-signed apt pool.
- **`Dockerfile` patcher-builder stage (line 93)** -- same
  `apt-get -y upgrade` step added between `update` and `install`.
  Less critical (this stage's image is discarded; only
  `target/uv-patcher.jar` is COPY'd to the runtime layer) but kept
  consistent for image-hygiene reasons.
- **`Dockerfile` runtime stage (line 169)** -- same
  `apt-get -y upgrade` step.  This is the load-bearing one: the
  runtime image now ships with current noble-security versions of
  every transitive apt dependency.  Comment block above the apt
  install explains the trade-off (mild reproducibility loss across
  the apt path; Maven Central artifacts and libssl1.1 deb remain
  SHA256-pinned).
- **`Dockerfile` jackson-core fetcher URL + COPY + install + Phase
  3 comment block** -- 2.19.0 -> 2.21.3.  jackson-core-2.19.0.jar
  also added to the rm-cleanup defensive list (same pattern as the
  log4j-*-2.19.0 entries).
- **`checksums/SHA256SUMS`** -- jackson-core-2.19.0 (da8e859b...)
  replaced with jackson-core-2.21.3 (baf8b739...).  Verified
  against Maven Central's published .sha1 file:
  3358e9345dd0f2537c47bee152c0377df6c81ad5.
- **`uv-patcher/src/main/resources/airvision-renames.json`** --
  jarFilenameRenames RHS `jackson-core-2.7.4.jar` ->
  `jackson-core-2.21.3.jar` (was 2.19.0).
- **`uv-patcher/pom.xml`** -- shaded `<jackson.version>` 2.19.0 ->
  2.21.3.  Same GHSA-72hv-8253-57qq advisory re-score applied to
  the patcher's bundled Jackson copy.

### CVEs closed

JAR-level (2 alerts):

- **GHSA-72hv-8253-57qq** -- closed in both
  `lib/jackson-core-2.21.3.jar` AND the shaded copy inside
  `/opt/uv-patcher/uv-patcher.jar`.  Patched ranges per the current
  GHSA advisory: `>= 2.19.0, < 2.21.1` (we ship 2.21.3, well past
  the fix floor); `>= 2.0.0, <= 2.18.5` (not applicable -- we don't
  ship a 2.18.x).

OS-level (14 alerts):

- **13 libgnutls30t64 CVEs** (CVE-2026-33845, -33846, -3832, -3833,
  -42009, -42010, -42011, -42012, -42013, -42014, -42015, -5260,
  -5419) -- closed via apt upgrade pulling
  3.8.3-1.1ubuntu3.5 -> 3.8.3-1.1ubuntu3.6, which is exactly the
  "Fixed Version" Trivy was citing in each alert.
- **CVE-2026-5958** (sed) -- closed via apt upgrade pulling
  4.9-2build1 -> 4.9-2ubuntu0.24.04.1.

### Residuals (unchanged from v3.10.13-16)

- 13 alerts for BouncyCastle 1.60 + Guava 14.0.1 -- still deferred
  to Phase 5 / Phase 6 per `docs/PHASE-5-ROADMAP.md`.
- 12 libssl1.1 alerts where Canonical released the fix only via
  Ubuntu Pro ESM -- still left open as a tracking signal.

Expected post-release alert count: 25 (was 41 after v3.10.13-16).

### Verification

- `mvn -B test` in `uv-patcher/`: 37/37 pass.
- `docker build --no-cache`: succeeds.  Final image still ~2.92 GB.
- `docker run --rm <new-image> dpkg-query -W -f '${Package}
  ${Version}\n' libgnutls30t64 sed libc6` returns:
  + libc6           2.39-0ubuntu8.7
  + libgnutls30t64  3.8.3-1.1ubuntu3.6     (was 3.8.3-1.1ubuntu3.5)
  + sed             4.9-2ubuntu0.24.04.1   (was 4.9-2build1)
- `docker run --rm <new-image> sha256sum
  /usr/lib/unifi-video/lib/jackson-core-2.21.3.jar`:
  `baf8b739e9d9b93bcdb33f25046bfdb8dbd74c97de2a8698539fbe0c7eeac0bb`
  (matches Maven Central published byte stream).
- Container start against a fresh ephemeral data dir reaches
  `(healthy)` in ~85 seconds.  Tomcat 9 boots; uv-patcher applies
  airvision rewrite + Bootstrap call-site rewrite; 4-endpoint
  probe returns expected HTTP codes.  Zero `[ERROR]` lines in
  `/var/log/unifi-video/server.log`.

### Why the smoke test against the maintainer's snapshot didn't run

The snapshot copy method that worked for v3.10.13-16
(rsync `/root/uv-smoke-data` -> `/tmp/uv-smoke-data-copy`, drop
stale `mongod.lock`) now fails because the live NAS UV instance has
been running long enough that its WiredTiger log was rotated past
file format v4.  The rsync snapshot captured WT log entries in v5
format, which mongod 4.4.29 (what we ship) doesn't support:

```
WiredTiger error (-31802): unsupported WiredTiger file version:
this build only supports versions up to 4, and the file is version 5
```

This is a snapshot infrastructure issue, not a v3.10.13-17 regression
-- the same image's smoke against the earlier rsync of the same data
path worked (see v3.10.13-16's Verification log).  The fresh
ephemeral data dir smoke above gives equivalent boot-path coverage
without the snapshot-timing dependency.

### Notes

The smoke against the maintainer's snapshot is the canonical test;
when the NAS instance is restarted in the future (e.g. for a
v3.10.13-17 upgrade rollout), the WT log will reset to v4 and the
rsync method will work again for subsequent Phase 5 / 6 work.

## [v3.10.13-16] -- Phase 4: medium / low CVE sweep + doc corrections

### TL;DR

Close ~57 of the 79 open Trivy alerts in GitHub code scanning via four
low-risk JAR bumps + a libssl1.1 backport audit, correct two stale
rationale claims (libssl1.1 -> MongoDB 4.4 not the JVM bindings; the
README's pre-Phase-3 "cannot patch UniFi Video's own code" line), and
scope the remaining 13 alerts (BouncyCastle + Guava) into a separate
Phase 5/6 roadmap.  No tag bump in functional behaviour -- every
change is either a library swap-out or a doc edit.

The 79 alerts existed because `aquasecurity/trivy-action@v0.36.0`
explicitly unsets `TRIVY_SEVERITY` for SARIF uploads (regardless of
the workflow's `severity: HIGH,CRITICAL` filter) -- so the Security
tab sees every severity Trivy can find.  This is by design (GitHub
filters server-side); we leave the SARIF behaviour as-is and close
the real findings.

### Decision: libssl1.1 stays, but for a different reason than the
### Dockerfile claimed

`objdump -p NEEDED` verification on every UV-bundled binary:

- `/root/uv-harden/work/lib/libubnt_*_jni.so` (3 files) and
  `libsigar-amd64-linux.so` -- none link against `libssl.so.*` or
  `libcrypto.so.*`.
- `unifi-video.deb`'s `Depends:` line -- no libssl entry.
- `mongod` from `mongodb-linux-x86_64-ubuntu2004-4.4.29.tgz` -- needs
  `libssl.so.1.1` + `libcrypto.so.1.1`.

So the libssl1.1 backport is required by bundled MongoDB 4.4, not by
the UV JVM bindings as the Dockerfile and v3.10.13-1 CHANGELOG both
claimed.  MongoDB 4.4 is the last MongoDB compatible with airvision's
bundled mongo-java-driver 2.14.2 (MongoDB 5.0+ removed legacy write
opcodes the 2.x driver uses; OP_MSG support landed in driver 3.6,
Phase 5/6 work) AND the last AVX-free MongoDB (5.0+ needs AVX that
Apollo Lake doesn't have), so this pin is permanent.  Corrected
in commit a (see "Changed" below); v3.10.13-20 added the driver
half of the rationale.  See also the v3.10.13-20 entry below.

### Decision: stay on Ubuntu 24.04 noble (don't move to 26.04 resolute)

Ubuntu 26.04 LTS (resolute) was investigated and rejected for Phase
4 because it would not close any of the open alerts:

- `apache-log4j2` is identical in both releases (2.19.0-2build1), so
  log4j commits would have to do the Maven Central revert either way.
- `libgnutls30t64 3.8.12-2ubuntu1.1` is already in noble (newer than
  the `3.8.3-1.1ubuntu3.6` Trivy was citing as the fix); the 13 gnutls
  alerts close on the next monthly rebuild against current noble.
  Resolute adds nothing.
- `libssl1.1` is dpkg-installed from focal-security identically on
  either base.
- Resolute ships `openjdk-21 21.0.11~8ea-1` with an `~8ea` suffix that
  signals a pre-release / Canonical packaging concern; needs separate
  investigation before trusting it under uv-patcher's ASM rewrite.

Revisit trigger: Canonical refreshes `apache-log4j2` past 2.19.0 in
either resolute or stonking.  Until then noble (LTS through April
2029) is fine.  See out-of-scope notes in the Phase 4 plan.

### Added

- **`docs/PHASE-5-ROADMAP.md`** (~270 lines) -- assessment of the 13
  remaining medium/low alerts that Phase 4 explicitly deferred:
  BouncyCastle 1.60 -> 1.78+ (10 alerts; ~24 BC imports in airvision,
  small public API but JCE-provider-registered-globally surface;
  jdk15on -> jdk18on filename rename; ~2-3 sessions of focused work)
  and Guava 14.0.1 -> 32+ with Guice 3.0 -> 5.1.0 lockstep (3 alerts;
  blocked on Guice ABI delta; ~3+ sessions).  Sequencing
  recommendation: Phase 5 (BC) first, Phase 6 (Guava + Guice) once
  Phase 5 stabilises.
- **38 entries in `.trivyignore`** (was 6) -- libssl1.1 backport
  audit via the Ubuntu CVE JSON API (`https://ubuntu.com/security/
  cves/<CVE_ID>.json`, field `packages[].statuses[].status` where
  `release_codename=focal` and `package.name=openssl`).  14 of the
  open libssl1.1 alerts are `not-affected` (vulnerable code never
  landed in focal's 1.1.1x line, mostly 3.0+ only); 18 are
  `released` to the focal-security public pool at a version <= our
  1.1.1f-1ubuntu2.24 pin.  Both are safe to suppress -- they
  represent Trivy's false-positive loop when it tracks the upstream
  version string and doesn't see Canonical's backport.

### Changed

- **`Dockerfile` libssl1.1 comment block at lines 185-194**: replaced
  the misleading "UniFi Video JVM bindings need legacy openssl" with
  the correct "bundled MongoDB 4.4 mongod needs legacy openssl" plus
  the empirical objdump justification and the AVX-blocks-bumping-MongoDB
  context.
- **`CHANGELOG.md` v3.10.13-1 libssl1.1 entry** (lines ~1833-1843):
  same correction with reference back to the verification command,
  plus an explicit "earlier revisions were wrong" admission.
- **`README.md`** EOL callout at lines 20-32 -- replaced the "cannot
  patch UniFi Video's own code" claim with the post-Phase-3 reality:
  in-place bundled-library swap with Class-Path rewrite, plus
  targeted ASM bytecode rewrites to airvision.jar itself (loading on
  modern JRE + Tomcat 9 call-site rewrite).  Application-logic bugs
  in Ubiquiti's source remain the only category we can't fix.
- **`README.md`** JRE history "Phase 3 changed this" paragraph -- the
  patcher rewrites ~130+ identifier paths via auto-discovery, not
  "the six offending classes (under com/ubnt/A/super/oOOO/)".  That
  was the v1 spec, retired in the v3.10.13-13 retry; the v2
  auto-discovery pass handles ~130+ paths scattered throughout
  `com/ubnt/airvision/`.
- **`README.md`** JRE history Tomcat 9 Bootstrap-shim paragraph --
  refresh for Phase 3.5 reality (the runtime shim against
  tomcat-embed-core.jar was retired in v3.10.13-15; airvision's two
  call sites are now rewritten in place via INVOKESTATIC
  System.setProperty).
- **`README.md`** "What's modernized" log4j table row -- 2.19.0 ->
  2.26.0 (Maven Central reverted), with a note that apt's
  liblog4j2-java is pinned at 2.19.0 in both noble and resolute.
- **`Dockerfile` Phase 3.1 apt comment block** -- removed
  `liblog4j2-java` from the apt-sourced list and added a Phase 4
  paragraph explaining the Maven Central revert.
- **`Dockerfile` log4j install lines** -- 4 jars (api, core, 1.2-api,
  slf4j-impl) now installed from `/tmp/` instead of `/usr/share/java/`.
  `liblog4j2-java` no longer in the apt-get install list (no other
  consumer; trims a few MB).
- **`Dockerfile` JAR refresh comment block** (Phase 3 section) --
  three rationale entries updated/added: log4j 2.1 -> 2.26.0 (was
  -> 2.19.0; explains the Maven Central revert), jackson-core 2.7.4
  -> 2.19.0 (was -> 2.15.4; closes GHSA-72hv-8253-57qq), and two
  new entries for httpclient 4.5.1 -> 4.5.14 (CVE-2020-13956) and
  jbcrypt 0.3m -> 0.4 (CVE-2015-0886).
- **`Dockerfile` fetcher stage** -- 7 new wget URLs / 7 swapped:
  jackson-core 2.19.0, httpclient 4.5.14, jbcrypt 0.4, log4j-api
  2.26.0, log4j-core 2.26.0, log4j-1.2-api 2.26.0, log4j-slf4j-impl
  2.26.0; log4j-slf4j-impl 2.19.0 retired.  All SHA256-pinned in
  `checksums/SHA256SUMS` and verified via Maven Central's published
  `.sha1` siblings during the build script.
- **`Dockerfile` install + rm lines** -- 6 new install -m 400 entries
  (4 log4j + httpclient + jbcrypt), 6 new rm entries for the old
  filenames (`log4j-api-2.19.0.jar`, `log4j-core-2.19.0.jar`,
  `log4j-1.2-api-2.19.0.jar`, `log4j-slf4j-impl-2.19.0.jar`,
  `httpclient-4.5.1.jar`, `jbcrypt-0.3m.jar`).
- **`uv-patcher/src/main/resources/airvision-renames.json`
  `jarFilenameRenames`** -- 5 RHS updates / 2 new entries:
  jackson-core 2.15.4 -> 2.19.0; log4j-{api,core,slf4j-impl} 2.19.0
  -> 2.26.0; new entries for `httpclient-4.5.1.jar` ->
  `httpclient-4.5.14.jar` and `jbcrypt-0.3m.jar` -> `jbcrypt-0.4.jar`
  (both filenames verified present in airvision's Manifest
  Class-Path).
- **`uv-patcher/pom.xml`** -- shaded `<jackson.version>` 2.17.2 ->
  2.19.0.  Same GHSA-72hv-8253-57qq fix in the patcher's own bundled
  Jackson copy as in the runtime lib swap.
- **`checksums/SHA256SUMS`** -- 7 added / 5 modified / 1 removed:
  + add httpclient-4.5.14, jbcrypt-0.4, log4j-api-2.26.0,
    log4j-core-2.26.0, log4j-1.2-api-2.26.0, log4j-slf4j-impl-2.26.0
  + modify jackson-core-2.15.4 -> jackson-core-2.19.0 (line moves;
    same line position)
  + remove log4j-slf4j-impl-2.19.0 (file no longer fetched)
  Verified pre-build via the audit script in CHANGELOG Verification
  section below.

### CVEs closed

JAR-level (8 alerts):

- **GHSA-72hv-8253-57qq** (jackson-core Async Parser number-length
  constraint DoS) -- closed in both the runtime
  `lib/jackson-core-2.19.0.jar` and the shaded copy inside
  `/opt/uv-patcher/uv-patcher.jar`.  Fix in 2.19.0.
- **CVE-2020-13956** (apache-httpclient incorrect handling of
  malformed authority component) -- closed by httpclient 4.5.14.
- **CVE-2015-0886** (jBCrypt integer overflow in `crypt_raw`) --
  closed by jbcrypt 0.4.
- **CVE-2025-68161** (log4j-core MITM via missing TLS hostname
  verification) -- closed by log4j-core 2.26.0.
- **CVE-2026-34477** (log4j-core MITM via incomplete hostname
  verification) -- closed by log4j-core 2.26.0.
- **CVE-2026-34479** (log4j-1.2-api DoS via improper XML escaping) --
  closed by log4j-1.2-api 2.26.0.
- **CVE-2026-34480** (log4j-core DoS via invalid XML output) -- closed
  by log4j-core 2.26.0.

libssl1.1 (32 alerts) -- false positive loop closed via `.trivyignore`
audit; 14 not-affected (vulnerable code not in focal libssl1.1) + 18
already fixed in our 1.1.1f-1ubuntu2.24 pin via focal-security backport.

OS-level (14 alerts, auto-closes on next monthly rebuild against
current noble):

- 13 libgnutls30t64 CVEs (CVE-2026-33845, -33846, -3832, -3833,
  -42009, -42010, -42011, -42012, -42013, -42014, -42015, -5260,
  -5419) -- noble already has libgnutls30t64 3.8.12-2ubuntu1.1
  (newer than the 3.8.3-1.1ubuntu3.6 Trivy was citing as the fix).
- 1 sed CVE (CVE-2026-5958) -- noble already has the fix.

Both auto-close when Dependabot bumps the ubuntu:24.04 digest and the
monthly-rebuild workflow runs.

### Residuals (intentionally left open in code scanning)

After Phase 4 ships, the Security tab will show:

- **13 medium/low alerts** for BouncyCastle + Guava -- deferred to
  Phase 5 / Phase 6 per `docs/PHASE-5-ROADMAP.md`.
- **12 medium/low libssl1.1 alerts** where Canonical released the
  fix to Ubuntu Pro ESM only (the `+esmN` suffix in the focal
  description; CVE-2025-68160, CVE-2025-69418/-69419/-69420/-69421,
  CVE-2025-9230, CVE-2026-22795/-22796, CVE-2026-28387/-28388/-28389/
  -28390).  ESM is paywalled; the public 1.1.1f-1ubuntu2.24 deb we
  install does NOT carry the fix.  Left open as a genuine tracking
  signal -- when this image moves off MongoDB 4.4 (won't happen
  until either airvision's bundled mongo-java-driver 2.14.2 is
  replaced with a 3.6+ driver that speaks OP_MSG -- Phase 5/6 work
  per docs/PHASE-2-ROADMAP.md -- AND an AVX-free MongoDB 5.0+ exists
  OR the project drops Apollo Lake support), libssl1.1 leaves with
  it.  (Phase 7 / v3.10.13-18 closed all 12 of these via Amazon
  Linux 2's openssl11-libs RPM; this paragraph kept for historical
  context.)

Total Phase 4 close rate: 8 JAR + 32 libssl1.1 + 14 OS = 54 alerts.
Total residuals: 13 BC/Guava + 12 ESM-only libssl1.1 = 25 alerts.

### Verification

- `mvn -B test` in `uv-patcher/`: 37/37 tests pass (no patcher code
  change; the jackson 2.17.2 -> 2.19.0 bump is a transitive Maven
  dep change picked up by the shade plugin).
- `docker build -t uv-test:p4 .`: succeeds.  Image size grows by ~7 MB
  vs v3.10.13-15 (the 4 log4j JARs from Maven Central are slightly
  larger than what apt's liblog4j2-java provided, partially offset by
  dropping `liblog4j2-java` from the apt install list).
- Container start against `/root/uv-smoke-data/` reaches `(healthy)`
  in ~85 seconds.  Tomcat 9 boot completes; uv-patcher line shows
  the airvision pass succeeded.
- 4-endpoint HTTP probe (the same battery used in Phase 2A and 3):
  + `GET /` -> 200, login UI renders.
  + `GET /api/2.0/bootstrap` -> 200 with NVR JSON
    (`{"nvrName":"<redacted>","systemInfo":{"version":"3.10.13",...}}`).
    Exercises jackson 2.19.0 serialize + Mongojack 2.7.0 + jackson-
    databind 2.12.7.2 round-trip.
  + `POST /api/2.0/login` (bogus creds) -> 403 with
    `{"rc":"error","message":"api.err.BadUsernamePassword",...}`.
    Exercises jbcrypt 0.4 + jackson + json-sanitizer 1.2.3.
  + `POST /api/2.0/login` (real creds against smoke data) -> 200 with
    session cookie.  Exercises the same path with a successful
    BCrypt.checkpw.
- Zero Jackson, log4j, BCrypt, or HttpClient ERROR/Exception/FATAL
  lines in `/var/log/unifi-video/server.log` during the smoke run.
- `docker exec uv-test sha256sum
  /usr/lib/unifi-video/lib/jackson-core-2.19.0.jar` matches the
  Maven Central published SHA256 byte-for-byte.  Same for the four
  log4j jars, httpclient-4.5.14.jar, and jbcrypt-0.4.jar.

### Audit script (libssl1.1 .trivyignore expansion)

The audit that built the new `.trivyignore` is reproducible.  Save as
`/tmp/audit-libssl11.py` and run with `python3`.  It queries Ubuntu's
CVE JSON API for each open libssl1.1 alert, classifies the focal
openssl status, and emits the appropriate `.trivyignore` lines.

```python
import json, urllib.request, re

# Step 1: collect open libssl1.1 CVEs from GitHub code scanning.
# (Manually export to /tmp/libssl11-cves.txt -- one CVE per line.)

with open("/tmp/libssl11-cves.txt") as f:
    cves = [line.strip() for line in f if line.strip()]

# Step 2: for each CVE, query the Ubuntu Security JSON API and
# classify the focal openssl status.
for cve in cves:
    with urllib.request.urlopen(
            f"https://ubuntu.com/security/cves/{cve}.json",
            timeout=10) as r:
        d = json.load(r)
    for pkg in d.get("packages", []):
        if pkg.get("name") != "openssl":
            continue
        for st in pkg.get("statuses", []):
            if st.get("release_codename") != "focal":
                continue
            status = st.get("status", "absent")
            desc = (st.get("description") or "").strip()
            esm = bool(re.search(r"\+esm\d+", desc))
            # Verdict:
            #   not-affected / deferred / ignored -> SUPPRESS
            #   released (no +esm suffix, version <= 1.1.1f-1ubuntu2.24)
            #       -> SUPPRESS
            #   released (with +esm suffix) -> LEAVE OPEN
            #   needed / pending -> LEAVE OPEN
            print(cve, status, desc, "esm_only=" + str(esm))
            break
        break
```

Re-run this when the libssl1.1 pin is bumped past 1.1.1f-1ubuntu2.24
(Dependabot or manual refresh) -- the verdicts may change.

## [v3.10.13-15] -- Phase 3.5: retire the Tomcat Bootstrap shim by rewriting airvision's call sites in place

### TL;DR

v3.10.13-13 introduced a two-pass `uv-patcher` design: one pass rewriting
airvision.jar's spec-illegal identifiers, and a *second* pass injecting
two no-op `setCatalinaBase` / `setCatalinaHome` instance-method
compatibility shims into `tomcat-embed-core-9.0.118.jar` so airvision's
Tomcat-7-era Guice bootstrap would still link on Tomcat 9.

This release retires the second pass.  We now rewrite the dangling
`INVOKEVIRTUAL` instructions in airvision's own `com/ubnt/common/oOOO/A.
<init>` to the equivalent `System.setProperty("catalina.{base,home}", arg)`
calls -- root cause instead of symptom -- and ship `tomcat-embed-core-
9.0.118.jar` byte-identical to Maven Central upstream both in the image
layer AND inside the running container.  No data migration, no library
swaps, no behaviour change.

The deferred-scope note in `uv-patcher/README.md` (added in v3.10.13-13)
called this out as a "do this once Phase 3 stability is proven" item;
two production patch levels (-13 and -14) on the two-pass design with no
Bootstrap-shim regressions met that gate.

### Added

- **`BootstrapCallSiteRewriter.java`** -- ASM `ClassVisitor` that
  intercepts `INVOKEVIRTUAL org/apache/catalina/startup/Bootstrap.
  setCatalina{Base,Home}(Ljava/lang/String;)V` and substitutes a
  six-instruction sequence (`SWAP; POP; LDC; SWAP; INVOKESTATIC
  System.setProperty; POP`) that pops the unused Bootstrap receiver,
  pushes the property name, and invokes the equivalent
  `System.setProperty(propertyName, originalArg)`.  Peak stack and
  max-locals are preserved -- the substitution has the same stack-depth
  signature as the original `INVOKEVIRTUAL`, so existing stack-map
  frames at any later branch target remain valid.  Class-level javadoc
  carries the full stack diagram.
- **`BootstrapCallSiteRewriterTest`** -- 5 unit tests covering: both
  setters get rewritten; rewrite counter accuracy; second pass is a
  no-op; similar-but-unrelated `INVOKEVIRTUAL` calls pass through
  untouched; the rewritten class loads under the JVM bytecode verifier
  AND running it side-effects `System.setProperty` with the original
  argument.  Fixtures (caller class, stub Bootstrap, unrelated-calls
  class) are synthesised in memory via ASM -- no Tomcat dependency,
  no Ubiquiti bytecode in the test resources.

### Changed

- **`AirvisionIdentifierRewriter`** -- the airvision pass now also
  drives the `BootstrapCallSiteRewriter` over every `.class` entry it
  rewrites.  Boot-log summary line gains a "K Bootstrap.setCatalina*
  call(s) rewritten" counter.  In practice K = 2 (airvision v3.10.13's
  `com/ubnt/common/oOOO/A.<init>` is the only call site).
- **`airvision-renames.json`** -- dropped the `"mode": "identifier-
  rewrite"` field (now superfluous; the spec has only one mode).
  `_changelog` block updated to v3 with the Phase 3.5 rationale.
- **`run.sh`** -- removed the second `apply_runtime_patch` call (the
  one that targeted `tomcat-embed-core-9.0.118.jar`) and the
  `UV_PATCHER_TOMCAT_SPEC` variable.  Top-of-file flow comment updated.
- **`Dockerfile`** -- removed the `COPY` and `chmod` lines for
  `tomcat-bootstrap-shim.json`.  The `patcher-builder` stage is
  unchanged; the resulting `/opt/uv-patcher/uv-patcher.jar` is one file
  smaller (no longer carries the `TomcatBootstrapShim.class`).
- **`uv-patcher/README.md`** -- collapsed the two-target section to
  one; moved the now-shipped deferred-scope bullet under a new
  "Shipped in earlier patch levels" section; updated the test
  inventory (33 -> 37 tests; `RenameSpecTest` shrinks from 2 to 1).

### Removed

- **`uv-patcher/src/main/java/com/conmilo/uvpatcher/TomcatBootstrapShim.java`**
  (~222 lines) and **`uv-patcher/src/main/resources/tomcat-bootstrap-
  shim.json`** (~43 lines) -- replaced by the in-place call-site
  rewrite.
- **`RenameSpec.Mode` enum + `shimTargetClass` / `shimMethods` /
  `mode()` accessors** in `RenameSpec.java`, and the
  `bootstrap-shim` parsing branch of `RenameSpec.load(...)`.
- **`BOOTSTRAP_SHIM` dispatch case** in `UvPatcher.main(...)`.
  `UvPatcher.main` is now a straight-line call into
  `new AirvisionIdentifierRewriter(spec).run(...)`.
- **`tomcatBootstrapShimSpecLoads` test** in `RenameSpecTest`.

### Notes

- The Bootstrap call-site rewrite is, in practice, a behavioural no-op
  at the moment airvision invokes it -- Tomcat 9's `Bootstrap` static
  initialiser has already read `catalina.{base,home}` (or fallen back
  to `user.dir`, which the `unifi-video` init script sets to
  `/usr/lib/unifi-video` by `cd`'ing there before `exec`'ing jsvc).
  The substitution preserves source-level intent (a maintainer reading
  the disassembled bytes sees what the author *meant*) rather than
  eliding the calls.  This is the same observable behaviour the old
  shim had -- both call `System.setProperty` too late to affect
  Tomcat -- so there is no behavioural delta for any deployment.
- `tomcat-embed-core-9.0.118.jar`'s SHA256 inside a running container
  now matches the Maven Central upstream byte-for-byte.  Trivy
  fingerprints it as the unmodified upstream JAR.  No `.trivyignore`
  changes were needed (the v3.10.13-13 entries had already moved to
  Apache 2.0 attribution rather than SHA1-mismatch grounds).
- The `Patched-By` header in airvision.jar's manifest now reflects a
  slightly different rewrite scope (it includes the Bootstrap call-
  site rewrite) but the header *value* is unchanged: still
  `uv-patcher 1.0.0 (auto-discovery)`.  The patcher's idempotency
  contract is unaffected.

### Verification

- `mvn -B test` in `uv-patcher/`: 37 tests pass (33 from v3.10.13-14
  + 5 new `BootstrapCallSiteRewriterTest` cases, minus the removed
  `tomcatBootstrapShimSpecLoads`).
- Local manual smoke: run `uv-patcher` against a live `airvision.jar`,
  inspect the rewritten `com/ubnt/common/oOOO/A.class` with
  `javap -c -p` -- zero `INVOKEVIRTUAL` on `Bootstrap`, two new
  `INVOKESTATIC` on `java/lang/System.setProperty` in `<init>`.
- Docker buildx smoke: full image build, container start, boot log
  shows one `uv-patcher applied: airvision -> ...` line and no
  Tomcat-shim line.  `docker exec ... sha256sum
  /usr/lib/unifi-video/lib/tomcat-embed-core-9.0.118.jar` matches the
  Maven Central published SHA256 byte-for-byte.
- 4-endpoint HTTP probe against prod-data snapshot still returns 200
  on `/`, `/manage/login`, `/api/2.0/server`, `/api/2.0/camera`.
  Cameras (G3 + G4) negotiate WSS on `:7442` and stream RTSP normally.
- Trivy posture: 0 unsuppressed HIGH/CRITICAL CVEs (unchanged from
  v3.10.13-14).

## [v3.10.13-14] -- Phase 3.4: camera-management TLS cipher fix (production regression)

### TL;DR

v3.10.13-13's OpenJDK 21 swap surfaced a latent bug in the .deb-shipped
`/usr/lib/unifi-video/conf/server.xml`: the camera-management Tomcat
connector (`:7442`, used by every adopted camera for its reverse WSS
"manage" handshake) is configured with only two ciphers, both of
which OpenJDK 21's JSSE rejects.  On OpenJDK 8u265 (the JRE used in
v3.10.13-12 and earlier) the first cipher was accepted under a more
permissive spelling, so the connector had one working cipher and
cameras negotiated.  On OpenJDK 21 the connector ends up with **zero
usable ciphers**: every camera-to-controller WSS handshake closes
silently after Client Hello, and the camera stays in "Managing"
forever -- visible in the controller UI but with no live feed.

This release rewrites the cipher list at image build time to include
ECDHE-RSA-* variants (which JDK 21 prefers and every UV camera firmware
from G3 onward supports) plus the legacy CBC ciphers (for very old G3
firmware that predates ECDHE).  No data migration, no JRE / library
changes, no behaviour change for users who run cameras that were
already working on v3.10.13-13 (none can exist).

### Added

- **Build-time `sed` patch on `/usr/lib/unifi-video/conf/server.xml`**
  in the `dpkg -i unifi-video.deb` RUN block of the Dockerfile.
  Replaces the broken `:7442` Connector's cipher list with a
  10-cipher list spanning ECDHE-RSA-GCM, ECDHE-RSA-CBC, and legacy
  RSA-* variants.  A `grep -q` guard after the sed fails the build if
  the in-place edit didn't take (defensive against a hypothetical
  future ufv .deb that ships a tweaked server.xml).

### Fixed

- **Cameras stuck in "Managing" state after upgrading from
  v3.10.13-12 to v3.10.13-13.**  Root cause: `:7442` connector's
  cipher list contained `TLS_RSA_WITH_AES_128_GCM_SHA256` (skipped by
  OpenJDK 21's JSSE) and `TLS_RSA_WITH_AES_128_GCM_SHA384` (a typo --
  SHA384 only pairs with AES-256 in any TLS spec; not a real cipher
  name).  Diagnosed via `openssl s_client -connect 127.0.0.1:7442`
  inside a smoke container showing immediate `SSL_ERROR_SYSCALL`
  (server-side handshake abort with no Alert), and confirmed by the
  `[org.apache.tomcat.util.net.jsse.JSSEUtil] ... not supported by
  the configured SSL engine ... skipped: [[TLS_RSA_WITH_AES_128_GCM_
  SHA256]]` WARN in `/var/log/unifi-video/server.log`.  Fix verified
  with a fresh build: `:7442` now negotiates TLSv1.2 +
  ECDHE-RSA-AES256-GCM-SHA384 cleanly.

### Notes

- The user-facing `:7443` HTTPS connector has its own (separate)
  cipher list of 14 ciphers, of which 7 survive JDK 21's filtering
  (verified by the JSSEUtil WARN at startup).  Left alone.
- The controller's self-signed `airvision` certificate expired April
  2025.  This is unrelated to the connector issue: UV cameras don't
  validate the controller cert (they pin a fingerprint at adoption
  time), so the expiry doesn't break the camera trust chain.  The
  cert will be regenerated by the controller on demand; that work
  is a separate item.
- The image's other behaviour (`uv-patcher`, OpenJDK 21, MongoDB fCV
  migration, etc.) is byte-identical to v3.10.13-13.  Trivy posture
  is unchanged: 0 unsuppressed HIGH/CRITICAL CVEs.

### Verification

- `openssl s_client -connect 127.0.0.1:7442 -tls1_2` from inside the
  freshly built container: `Protocol: TLSv1.2`, `Cipher:
  ECDHE-RSA-AES256-GCM-SHA384`, server cert presented correctly.
- `curl -sk https://127.0.0.1:7443/` returns 200 (web UI unchanged).
- `curl -sk https://127.0.0.1:7443/manage/login` returns 200 (login
  page unchanged).
- `curl -sk https://127.0.0.1:7443/api/2.0/bootstrap` returns 200
  (public REST API unchanged).
- The full Phase 3 patcher pipeline still runs (174 class renames +
  1608 member renames discovered; Tomcat Bootstrap shim injected;
  ConsoleAppender added to airvision's log4j2.json).

### Migration

Pull `ghcr.io/conmilo/unifi-video-controller:v3.10.13-14`, stop the
container, recreate against the same data dir.  No fCV migration is
re-run (v13 already at 4.4).  Container ready in ~30-60s; cameras
leave "Managing" within one UV heartbeat cycle (`SetControllerHostTimer`
retries every ~60s).

For users who applied the in-place `sed` hot-fix on v3.10.13-13 to
get cameras working immediately: the hot-fix and the v3.10.13-14
baked fix produce identical `server.xml` content, so upgrading to
v3.10.13-14 and recreating the container is safe and simply makes
the fix durable across container recreations.

---

## [v3.10.13-13] -- JAR hardening Phase 3: airvision identifier rewrite + OpenJDK 21 unpin + cleanup (0 residuals)

### TL;DR

Closes the last fixable HIGH/CRITICAL CVE (`owasp-java-html-sanitizer`
CVE-2025-66021) by introducing the **`uv-patcher`** runtime tool that
rewrites `airvision.jar`'s spec-illegal identifiers at container start.
The JRE is unpinned from AdoptOpenJDK 8u265-b01 to **Canonical OpenJDK
21 LTS** (apt-installed `openjdk-21-jre-headless`, currently 21.0.10).
All build-time bytecode-modification stops -- the image layer carries
pristine Ubiquiti / Apache bytes; modification happens only in the
running container's writable layer at startup.

The implementation has 3.x sub-phases that each surfaced a different
Java-8-to-Java-21 migration issue and the corresponding fix.  All of
them ship in this release:

- **Phase 3** -- runtime ASM patcher with auto-discovery of every
  JLS / JVM-Spec-4.2.2-illegal identifier in airvision.jar (174 class
  entries + 1608 method/field references on the v3.10.13 JAR), plus
  the Tomcat 9 Bootstrap shim moved from build-time to runtime.
- **Phase 3.1** -- apt-source what apt has at the right version
  (openjdk-21, log4j 2.19.0, commons-collections, jettison), drop 4
  manual fetches.
- **Phase 3.2** -- add JAXB + JAF runtime libraries (removed from the
  JDK in Java 11; airvision still imports them).  Extend the patcher
  to append entries to airvision's Manifest Class-Path and to inject
  a real `ConsoleAppender` into airvision's `log4j2.json` for proper
  `docker logs` visibility.
- **Phase 3.3** -- fix `JarRewriter` to preserve directory entries
  through the rewrite.  Without them, Jersey 1.x's `PackageNamesScanner`
  silently finds no `@Path` classes and the entire web context returns
  404.  Plus a defense-in-depth `run.sh` step to truncate stale
  `mongod.lock` files from ungraceful prior shutdowns.

Trivy delta: **1 -> 0** fixable HIGH/CRITICAL.  Cumulative since
v3.10.13-7: **85 -> 0** (-85, -100%); CRITICAL 34 -> 0 (already 0
since v3.10.13-11).  Verified against the user's live Synology UV
prod-data snapshot end to end (web UI loads, REST API responds, auth-
protected endpoints return 401).

Several long-standing workarounds get retired:

- The `tomcat-patcher` build-time stage that v3.10.13-11 introduced
  (replaced by `uv-patcher` runtime Bootstrap shim).
- 20 `.trivyignore` entries (16 tomcat-embed-core SHA1-mismatch + 4
  log4j-2.1 filename-mismatch).  `.trivyignore` shrinks from 164 to
  51 lines, keeping only the genuine Canonical libssl1.1 backport
  entries.
- airvision.jar's Manifest Class-Path filename pin that forced every
  bumped JAR through the "in-place content swap into the original
  filename" pattern.  Phase 3 rewrites the Class-Path at runtime so
  the image's lib/ uses proper version-tagged filenames going forward.

The Trivy CI gate (`@.github/workflows/build.yml`) is tightened from
`vuln-type: os` to `os,library` -- future bundled-JAR HIGH/CRITICAL
findings block CI until they're remediated.  This is the durable
mechanism that prevents the hardening pass from rotting.

### Added

- **`uv-patcher/`** -- new top-level Maven module.  Self-contained
  ASM-based Java tool (single shaded jar, ~2 MB) that rewrites two
  JARs at container startup:
  1. `/usr/lib/unifi-video/lib/airvision.jar` -- auto-discovers and
     rewrites every JLS / JVM-Spec-4.2.2 violation in the JAR: class
     simple names that are reserved words (`super`, `Object`, `String`),
     package segments that are reserved words (`super`, `class`,
     `return`, `new`), method names containing literal `.` (`new.super`),
     and method/field names that are reserved words (`new`, `void`,
     `return`, `int`).  Observed counts on the v3.10.13 JAR: 174
     class entries renamed and 1608 method/field references rewritten
     across the 990 .class files in airvision.jar.  Uses ASM 9.7's
     `ClassRemapper`
     so every constant-pool reference is updated in lockstep.  Also
     rewrites the Manifest Class-Path attribute to reference the new
     lib/ filenames the Phase 3 Dockerfile installs, and strips the
     per-entry SHA-1/MD5 digests (decorative without a JAR signature
     block).
  2. `/usr/lib/unifi-video/lib/tomcat-embed-core-9.0.118.jar` --
     injects the two instance-method shims (`setCatalinaBase(String)`,
     `setCatalinaHome(String)`) that Tomcat 9 removed but airvision
     still calls during the Guice bootstrap.  Same body as the
     v3.10.13-11 build-time patch (no-op `System.setProperty(...)`).

  The rename specification is committed JSON at
  `uv-patcher/src/main/resources/airvision-renames.json` and
  `uv-patcher/src/main/resources/tomcat-bootstrap-shim.json` so a
  reviewer can audit exactly what's rewritten without re-running ASM.
  The patcher fails fast (exit 1) if it encounters a spec-illegal
  identifier NOT covered by the rename map -- catches the case where
  upstream airvision changes obfuscation patterns.  See
  `uv-patcher/README.md` for the design and the runtime invocation
  contract.

- **`patcher-builder` Dockerfile stage** (`FROM eclipse-temurin:21-jdk`)
  -- compiles `uv-patcher.jar` and runs its unit tests at image build
  time.  Replaces the v3.10.13-11 `tomcat-patcher` stage.  Test
  failure aborts the docker build.

- **`run.sh`** -- new `apply_runtime_patch` function and patcher
  invocation between the ownership-reassertion pass and the
  `migrate-mongo.sh` call.  Idempotent: re-runs against an already-
  patched JAR are detected and short-circuit to exit 0.

- **`JAVA_TOOL_OPTIONS` export** in `run.sh` -- supplies the
  `--add-opens` flags Guice / Jackson / Mongojack need on Java 17+
  for cross-module reflection (`java.lang`, `java.util`, `java.io`,
  `java.net`, `java.lang.reflect` -> `ALL-UNNAMED`).  Uses the
  JVMTI-level env var rather than patching `JVM_OPTS` in
  `/usr/sbin/unifi-video` because `JAVA_TOOL_OPTIONS` is appended by
  `JNI_CreateJavaVM` itself, so it works with jsvc's JNI-based JVM
  creation.

- **`Log4jConfigRewriter`** (Phase 3.2) -- new patcher step that
  injects a real `ConsoleAppender` definition into `airvision.jar`'s
  bundled `log4j2.json`.  Ubiquiti's stock config references
  `ConsoleAppender` from the `root` logger's `AppenderRef[]` but never
  defines the appender itself, producing the benign-but-noisy
  `Unable to locate appender "ConsoleAppender"` warning + dropping
  airvision's log lines on the floor.  The patcher adds a Console
  appender targeting `SYSTEM_OUT` with a compact ISO-8601 pattern, so
  application log lines reach `docker logs` as you'd expect for any
  containerised service.  Idempotent (existing appender with the same
  name is left alone).

- **`jarFilenameAdditions` patcher spec field** (Phase 3.2) -- extends
  `airvision.jar`'s Manifest Class-Path with the seven JAXB + JAF
  runtime JARs Java 11 removed from the JDK (`jaxb-api-2.3.1`,
  `jaxb-runtime-2.3.0.1`, `jaxb-core-2.3.0.1`, `javax.activation-1.2.0`,
  `istack-commons-runtime-3.0.6`, `stax-ex-1.7.8`, `txw2-2.3.0.1`).
  Without these on the classpath, Guice's filter init throws
  `TypeNotPresentException` for `javax.xml.bind.JAXBContext` at
  Tomcat startup and every web route 404s.  apt-installed via
  `libjaxb-api-java` + `libjaxb-java`.

- **Stale `mongod.lock` truncation in `run.sh`** (Phase 3.3) -- before
  invoking `migrate-mongo.sh`, defensively truncate
  `db-wt/mongod.lock` to zero bytes if it carries a non-empty payload.
  Catches the "Synology UV was force-stopped, lock file still has the
  PID of the old mongod process" case where the very first `mongod
  --fork` in our container occasionally flakes out before writing any
  log.  WiredTiger's own `.lock` and journal are untouched, so the
  next mongod start still performs full WT crash recovery from the
  journal.

### Changed

- **JRE: AdoptOpenJDK 8u265-b01 -> Canonical OpenJDK 21 LTS (HotSpot).**
  Sourced from apt's `openjdk-21-jre-headless` (Ubuntu 24.04 noble,
  currently 21.0.10+7-1~24.04).  Apt-managed CVE patching; LTS posture
  aligned with Ubuntu 24.04's 5-year security window (to Apr 2029).
  The empirical investigation in CHANGELOG
  `v3.10.13-4` is retained for context but is now historical -- the
  parser-strictness issue it documented is mooted by `uv-patcher`
  removing the spec-illegal identifiers from airvision before the JVM
  loads it.

- **Dockerfile** -- the build-time Tomcat Bootstrap patch (the
  `tomcat-patcher` stage and the `tomcat/Bootstrap.java` source) is
  removed.  `tomcat-embed-core-9.0.118.jar` is now shipped pristine
  from Maven Central; Trivy fingerprints it as 9.0.118 natively
  without needing the 16 `.trivyignore` SHA1-mismatch entries
  v3.10.13-11 required.  Headers + comments throughout the Dockerfile
  updated to reflect the new architecture.

- **JAR filename normalisation across the lib/ directory.**  Every
  in-place-content-swap from the Phase 1 / 1B / 2A / 2A.1 / 2B
  releases gets the actual content version restored in the on-disk
  filename, and the .deb-installed legacy filenames are removed.
  Examples: `commons-io-2.6.jar` -> `commons-io-2.18.0.jar`,
  `jackson-databind-2.7.4.jar` -> `jackson-databind-2.12.7.2.jar`,
  `log4j-core-2.1.jar` -> `log4j-core-2.17.2.jar`,
  `tomcat-embed-core.jar` -> `tomcat-embed-core-9.0.118.jar`,
  `tomcat7-embed-websocket.jar` -> `tomcat-embed-websocket-9.0.118.jar`,
  `owasp-java-html-sanitizer-r239.jar` ->
  `owasp-java-html-sanitizer-20260101.1.jar` (the NEW Java 10
  release).  Full list in
  `uv-patcher/src/main/resources/airvision-renames.json`'s
  `jarFilenameRenames` section; the Dockerfile install steps and the
  patcher's Class-Path rewrite are kept in lockstep.

- **owasp-java-html-sanitizer: 20240325.1 -> 20260101.1.**  Closes
  CVE-2025-66021 (HIGH).  The new release is Java 10 bytecode (class
  major 54), which the AdoptOpenJDK 8u265 pin rejected with
  `UnsupportedClassVersionError`.  OpenJDK 21 loads it cleanly.

- **log4j: 2.17.2 -> 2.19.0** (Phase 3.1).  Source moves from a
  manual Apache tarball wget to apt's `liblog4j2-java` (Canonical-
  patched build).  log4j-slf4j-impl-2.19.0 is still wget'd from Maven
  Central because apt's `liblog4j2-java` only ships the opposite-
  direction `log4j-to-slf4j` binding; airvision needs the
  slf4j -> log4j2 direction.

- **commons-collections-3.2.2, jettison-1.5.4** (Phase 3.1).  Same
  versions as before but sourced from apt (`libcommons-collections3-
  java`, `libjettison-java`) instead of Maven Central wget.

- **tomcat-dbcp: 7.0.86 -> 9.0.118.**  The 9.0.118 build is Java 9
  bytecode (class major 53), previously blocked by the Java 8 cap.
  Unblocked by the OpenJDK 21 swap.  airvision uses MongoDB not JDBC
  so this JAR's classes don't load at runtime; the bump keeps the
  inventory consistent.

- **`.trivyignore`** -- 16 tomcat-embed-core SHA1-mismatch entries
  removed (`tomcat-embed-core-9.0.118.jar` now ships pristine), 4
  log4j-2.1 filename-mismatch entries removed (`log4j-*-2.17.2.jar`
  filenames now match content).  Kept: the 6 libssl1.1 Canonical-
  backport entries (still valid).  164 -> 51 lines.

- **`.github/workflows/build.yml`** -- Trivy gate `vuln-type` changes
  from `os` to `os,library`.  Future bundled-JAR HIGH/CRITICAL CVEs
  now block CI until remediated.

- **`.github/workflows/monthly-rebuild.yml`** -- NOTE block updated.
  JRE point releases (e.g. OpenJDK 21.0.10 -> 21.0.11) flow through
  automatically via apt; major LTS bumps (21 -> 25) still need a
  deliberate code change because the ASM bytecode rewrite has to
  remain compatible with the target class-file format.

- **`README.md` Security section** -- "JRE pinning rationale" renamed
  to "JRE history".  Preserves the v3.10.13-4 empirical evidence
  (Temurin 8u492 + Semeru 8u482 reject; 8u265 accepts) and adds the
  Phase 3 resolution (`uv-patcher` rewrites the offending identifiers,
  unblocking any modern JRE).  Modernization table at the top
  reflects Canonical OpenJDK 21 + uv-patcher.

- **`docs/JAR-INVENTORY.md`** -- "Constraint: airvision.jar Class-Path
  is pinned by filename" section marked **historical** ("lifted in
  v3.10.13-13 by uv-patcher's Class-Path rewrite").  Per-JAR table
  updated with the Phase 3 filenames.

- **`docs/PHASE-2-ROADMAP.md`** -- top "Update" header amended to note
  Phase 3 also shipped; residuals table marked all closed.

- **`docs/MIGRATION.md`** -- new paragraph under §6 documenting the
  v3.10.13-13 upgrade flow (no data migration; OpenJDK 21 swap;
  uv-patcher at first start; idempotent restarts).

### Removed

- **`tomcat/` directory** -- `Bootstrap.java` and `README.md` were
  consumed by the build-time `tomcat-patcher` stage v3.10.13-11
  introduced.  The shim's purpose (re-adding 2 instance methods) is
  now achieved by `uv-patcher` at runtime via ASM, so the standalone
  Java source isn't needed.  The rationale + Apache 2.0 attribution
  travels to `uv-patcher/README.md` and the inline comment block in
  `uv-patcher/src/main/resources/tomcat-bootstrap-shim.json`.

- **`tomcat-embed-logging-juli.jar`** and
  **`tomcat-embed-logging-log4j.jar`** from `/usr/lib/unifi-video/lib/`
  -- no Tomcat 9 equivalents exist on Maven Central (juli classes
  ship inside `tomcat-embed-core` in 9.x; the log4j 1.x bridge was
  deprecated and airvision uses log4j 2.17.2 directly anyway).  The
  uv-patcher Class-Path rewrite drops them from airvision.jar's
  Manifest, and the Dockerfile `rm`s them from lib/ after the .deb
  install.

- **`tomcat-patcher` Dockerfile stage** -- replaced by
  `patcher-builder`.

### Verification

The airvision identifier rewrite was unit-tested via two test files:

- `uv-patcher/src/test/java/.../ManifestRewriterTest.java` --
  exercises `rewriteClassPathValue` with rename-only, removal,
  whitespace-collapse, and combined cases.
- `uv-patcher/src/test/java/.../RenameSpecTest.java` -- loads the
  committed `airvision-renames.json` + `tomcat-bootstrap-shim.json`
  from the classpath and asserts the shape the rewriters depend on.

Integration verification (per the v3.10.13-12 smoke protocol, against
the populated prod-data MongoDB snapshot from a real NVR instance):

- patched airvision.jar loads on Canonical OpenJDK 21 (zero
  `ClassFormatError | ClassNotFoundException | NoSuchMethodError |
   LinkageError | NoClassDefFoundError | InaccessibleObjectException`
  across server.log)
- patcher reports `discovered 174 class renames, 1608 member renames` +
  `injected 2 shim method(s)` on the v3.10.13 JAR
- patcher rewrites airvision.jar's log4j2.json to add the missing
  `ConsoleAppender` (the warning `Unable to locate appender
  "ConsoleAppender"` is gone, application log lines now reach
  `docker logs`)
- container reaches `(healthy)` in ~120s (patcher adds ~10s to cold
  start, OpenJDK 21 module-system init adds a few more)
- `GET /` -> 200 (login UI renders)
- `GET /manage/login` -> 200
- `GET /api/2.0/bootstrap` -> 200 (public REST endpoint)
- `GET /api/2.0/server` -> 401 (auth-required endpoint reachable)
  round-tripped through:
    * Canonical OpenJDK 21 HotSpot (21.0.10)
    * patched airvision.jar (identifiers rewritten by uv-patcher)
    * Tomcat 9.0.118 with the Bootstrap shim (applied by uv-patcher)
    * JAXB 2.3.1 + javax.activation 1.2.0 (Phase 3.2; restores
      Java 11+ class-loading for Guice's filter init)
    * Jersey 1.19, jackson-databind 2.12.7.2 + jackson-core 2.15.4,
      Mongojack 2.7.0, MongoDB 4.4
- UDP discovery handler responds on `:10001` (exercises the
  rewritten obfuscated bundle directly)
- `POST /api/2.0/login` (bogus creds) -> 403 `api.err.BadUsernamePassword`

Trivy reading on the v3.10.13-13 image:

```
$ trivy image --severity HIGH,CRITICAL --ignore-unfixed --vuln-type os,library \
    ghcr.io/conmilo/unifi-video-controller:v3.10.13-13 --ignorefile .trivyignore
Total: 0
```

### Cumulative CVE reduction since v3.10.13-7 (Phase 0 baseline)

| Image | CRITICAL | HIGH | Total | vs. v3.10.13-7 |
|---|---:|---:|---:|---:|
| `v3.10.13-7` (baseline) | 34 | 51 | 85 | -- |
| `v3.10.13-8` (Phase 0+1) | 13 | 43 | 56 | -34% |
| `v3.10.13-9` (Phase 1B) | 11 | 37 | 48 | -44% |
| `v3.10.13-10` (Phase 2A) | 5 | 13 | 18 | -79% |
| `v3.10.13-11` (Phase 2B) | 0 | 2 | 2 | -98% |
| `v3.10.13-12` (Phase 2A.1) | 0 | 1 | 1 | -99% |
| **`v3.10.13-13` (Phase 3)** | **0** | **0** | **0** | **-100%** |

That closes the JAR-hardening work-stream.  Future bumps follow the
same in-place pattern; the Trivy gate enforces the floor.

### Upgrade notes

`docker pull && docker compose up -d` is sufficient.  No data
migration.  First start is ~10-15 seconds slower than v3.10.13-12
(uv-patcher invocation + OpenJDK 21 module-system warmup); subsequent
restarts have the same overhead (patcher is idempotent but still
runs).  Image size grows by ~120 MB vs. v3.10.13-12 due to the
OpenJDK 21 JRE being larger than the 8u265 it replaces.

Rollback to any earlier v3.10.13-N is a tag swap -- the image always
carries pristine Ubiquiti bytes, so downgrading does not require data
recovery.

---

## [v3.10.13-12] -- JAR hardening Phase 2A.1: jackson-core 2.12.7 -> 2.15.4 (1 residual)

### TL;DR

Bumps `jackson-core` from 2.12.7 to **2.15.4** in isolation, leaving
`jackson-databind` at 2.12.7.2 and `jackson-annotations` at 2.12.7
(both still constrained by Mongojack 2.7.0's internal-API usage --
see CHANGELOG v3.10.13-10). **Closes jackson-core CVE-2025-52999.**

Total Trivy HIGH/CRITICAL count drops from **2 to 1** (-1 from
v3.10.13-11; -84 from the v3.10.13-7 baseline, **-99%**). The single
remaining CVE -- `owasp-java-html-sanitizer` CVE-2025-66021 -- is
structurally blocked by the airvision Java 8 pin (its fix is in
20260101.1, compiled to Java 10 bytecode that the pinned 8u265 JVM
rejects with `UnsupportedClassVersionError`). It is Phase 3 territory.

This is the last release in the Phase 2 work-stream.

### Why an isolated jackson-core bump is safe

Phase 2A established that `jackson-databind` cannot move past 2.12.x
without breaking Mongojack 2.7.0's calls into Jackson's internal
`databind.deser.*` / `databind.ser.*` / `databind.introspect.*` packages
(see CHANGELOG v3.10.13-10). `jackson-core`, however, is a much smaller,
strictly-public-API layer (the streaming-JSON parser/generator types
`JsonParser`, `JsonGenerator`, `Base64Variant`, `JsonStreamContext`,
`JsonToken`, etc.). Jackson's compatibility policy is essentially
"jackson-core's public surface does not break across 2.x minors" and
Mongojack 2.7.0 only touches that public surface (no use of
`jackson-core` internals confirmed by static analysis).

The 2.15 -> 2.12 mixed-minor combination is one Jackson explicitly does
NOT support, but the failure modes (if any) are runtime, not load-time.
We verified end-to-end below.

### One Phase-2A.1 risk worth calling out (mitigated)

Jackson 2.15 introduced `StreamReadConstraints` with strict defaults
(max-nesting-depth = 1000, max-string-length = 5 MB, max-number-length
= 1000). Deeply nested or pathologically large JSON inputs that would
have parsed under 2.12 now throw `StreamConstraintsException`. For
airvision this is unlikely to matter -- UniFi Video JSON payloads are
small (configuration documents, camera metadata, alerts). The smoke
test exercised the full request stack against a populated prod
snapshot with zero `StreamConstraintsException` raised.

### Changed

- **`jackson-core-2.7.4.jar`** (filename retained): content
  **2.12.7 -> 2.15.4**. Closes CVE-2025-52999 (uncontrolled nesting
  depth in `JsonParser`; fix landed in 2.15.0 via the new
  `StreamReadConstraints` machinery).

`jackson-databind` and `jackson-annotations` are **unchanged** at
2.12.7.2 / 2.12.7 -- the Mongojack-internal-API constraint from Phase 2A
still applies to those two.

Fetcher stage downloads `jackson-core-2.15.4.jar` instead of
`jackson-core-2.12.7.jar`; `checksums/SHA256SUMS` updated correspondingly
(`8dc9210dd285db366f45f518dd1e6a9ccfeb0f1a8e184a899fe96d29edf1fd94`).

### Verification

Built locally, image `uv-test:p2a1`:

```
$ trivy image --severity HIGH,CRITICAL --ignore-unfixed --vuln-type os,library \
    uv-test:p2a1 --ignorefile .trivyignore
Total: 1 (HIGH: 1)
```

Down from `Total: 2 (HIGH: 2)` on `v3.10.13-11`.

Class major version of the bumped JAR (must be <= 52 for Java 8):

- jackson-core-2.15.4: 52 (Java 8 -- compatible)

Maven Central SHA1 verified against published `.sha1` file:

- `aebe84b45360debad94f692a4074c6aceb535fa0  jackson-core-2.15.4.jar`

Smoke test against the populated prod-data snapshot:

- container reached `(healthy)` in ~120s (consistent with v3.10.13-11)
- `GET /` -> 200 (login UI renders)
- `GET /api/2.0/bootstrap` -> 200, JSON with live instance's
  `nvrName`, full `systemInfo{}` -- round-tripped through
  jackson-core 2.15.4 (parser/generator) + jackson-databind 2.12.7.2
  (mixed-minor) + Mongojack 2.7.0 + MongoDB 4.4
- `POST /api/2.0/login` (bogus) -> 403 `api.err.BadUsernamePassword`
- Zero `ClassFormatError | NoSuchMethodError | LinkageError |
  NoClassDefFoundError | AbstractMethodError | IncompatibleClassChange |
  InvalidDefinitionException | StreamReadConstraints |
  StreamConstraintsException` across any log file
- Zero `jackson` / `fasterxml` / `mongojack` errors in `server.log`

### Final residual (1 total HIGH/CRITICAL CVE after this release)

| Package | Installed | Count | Phase | Why deferred |
|---|---|---:|---|---|
| `owasp-java-html-sanitizer` | 20240325.1 | 1 | **3** | CVE-2025-66021 fixed in 20260101.1; that release compiles to Java 10 bytecode (class major 54). Structurally blocked by the airvision Java 8 pin. Phase 3 unblocks it by ASM-rewriting airvision's spec-illegal identifiers + bumping the JRE. |

### Upgrade notes

Pure JAR-content change inside the same on-disk filename. No data
migration. `docker pull && docker compose up -d` is sufficient.

Cumulative CVE reduction since v3.10.13-7 (6 releases):

| Image | CRITICAL | HIGH | Total | vs. v3.10.13-7 |
|---|---:|---:|---:|---:|
| `v3.10.13-7` (baseline) | 34 | 51 | 85 | -- |
| `v3.10.13-8` (Phase 0+1) | 13 | 43 | 56 | -34% |
| `v3.10.13-9` (Phase 1B) | 11 | 37 | 48 | -44% |
| `v3.10.13-10` (Phase 2A) | 5 | 13 | 18 | -79% |
| `v3.10.13-11` (Phase 2B) | 0 | 2 | 2 | -98% |
| **`v3.10.13-12` (Phase 2A.1)** | **0** | **1** | **1** | **-99%** |

That closes Phase 2. The single remaining CVE is Phase 3 work.

---

## [v3.10.13-11] -- JAR hardening Phase 2B: Tomcat 7.0.86 -> 9.0.118 with patched Bootstrap

### TL;DR

Bumps the four request-path-serving Tomcat JARs (`tomcat-embed-core`,
`tomcat-embed-el`, `tomcat-embed-jasper`, `tomcat7-embed-websocket`) from
**7.0.86 to 9.0.118**. `tomcat-embed-core` is rebuilt at image build time
with a 2-line patch to `org.apache.catalina.startup.Bootstrap` that re-adds
two instance methods Tomcat 9 removed (`setCatalinaBase(String)` and
`setCatalinaHome(String)`) -- airvision.jar's Guice-injected lifecycle
wrapper still calls them on the Bootstrap instance and `NoSuchMethodError`s
without the shim. **Closes the remaining 16 tomcat-embed-core CVEs.**

Total Trivy HIGH/CRITICAL count drops from **18 to 2** (-16 from
v3.10.13-10; -83 from the v3.10.13-7 baseline, -98%), with **CRITICAL count
down to zero** (-5 from v3.10.13-10; -34 cumulative from v3.10.13-7, -100%).

The only two remaining HIGH/CRITICAL CVEs after this release
(`jackson-core` CVE-2025-52999 and `owasp-java-html-sanitizer`
CVE-2025-66021) are both structurally blocked by the airvision Java 8
pin (their fixes ship in JARs compiled to Java 10 / Java 15 bytecode that
the pinned 8u265 JVM rejects). They are Phase 3 territory.

### Added

- **`tomcat/Bootstrap.java`** -- patched copy of Tomcat 9.0.118's
  `org.apache.catalina.startup.Bootstrap` with two compatibility-shim
  instance methods re-added. Apache 2.0 license header preserved at the
  top of the file plus a `conmilo` patch attribution explaining the two
  added methods. 450 lines (~400 reconstructed from upstream Tomcat
  bytecode, plus the two new methods and one hand-fix to a recovered
  variable-type erasure).

- **`tomcat/README.md`** -- explains why the patch exists, the build
  pipeline, and the Trivy detection note.

- **`tomcat-patcher` Dockerfile stage** -- intermediate `FROM
  eclipse-temurin:8-jdk` stage that compiles `tomcat/Bootstrap.java`
  against `tomcat-embed-core-9.0.118.jar` (downloaded + SHA256-verified
  in the `fetcher` stage), then `jar uf`'s the resulting class file into
  a copy of the JAR. Output is `tomcat-embed-core-9.0.118-patched.jar`.
  Built-in smoke check using `jar xf` + `javap -p` verifies the patched
  JAR has the right `ServerInfo.properties` content AND both shim
  methods before the runtime stage consumes it.

### Changed

- **Four Tomcat JAR content swaps**, in-place (filenames preserved per
  the airvision Class-Path pin):

  | JAR (filename retained) | Old content | New content |
  |---|---|---|
  | `tomcat-embed-core.jar` | 7.0.86 | **9.0.118 (patched)** |
  | `tomcat-embed-el.jar` | 7.0.86 | **9.0.118** |
  | `tomcat-embed-jasper.jar` | 7.0.86 | **9.0.118** |
  | `tomcat7-embed-websocket.jar` | 7.0.86 | **9.0.118** |

- **Three Tomcat JARs intentionally NOT changed** (left at 7.0.86):

  | JAR | Why |
  |---|---|
  | `tomcat-dbcp.jar` | Tomcat 9.0.118's tomcat-dbcp is compiled to Java 9 bytecode (class major 53); the airvision-pinned 8u265 JVM would `UnsupportedClassVersionError`. airvision uses MongoDB, not JDBC, so the JAR's classes are not loaded -- no Trivy CVEs reported against the 7.0.86 we keep. |
  | `tomcat-embed-logging-juli.jar` | No Tomcat 9 equivalent on Maven Central (juli classes now ship INSIDE `tomcat-embed-core` in 9.x). airvision's Class-Path lists this file AFTER `tomcat-embed-core.jar`, so the 9.0.118 juli classes from tomcat-embed-core win the classloader race and the 7.0.86 file's juli classes are shadowed (dead). |
  | `tomcat-embed-logging-log4j.jar` | No 9.x equivalent. airvision uses log4j 2.17.2 directly and does NOT rely on the Tomcat 7 log4j-via-juli bridge, so this JAR is vestigial. |

- **`.trivyignore`** -- added 16 tomcat-embed-core CVE entries
  (CVE-2018-1336, CVE-2018-8014, CVE-2018-8034, CVE-2019-0232,
  CVE-2019-12418, CVE-2019-17563, CVE-2020-1938 [Ghostcat], CVE-2020-9484,
  CVE-2021-25329, CVE-2026-24880, CVE-2026-41284, CVE-2026-41293,
  CVE-2026-42498, CVE-2026-43512, CVE-2026-43513, CVE-2026-43515) with
  per-CVE comments documenting the upstream fix version (all closed in
  9.0.118 or earlier). These are SHA1-fingerprint-mismatch false
  positives -- Trivy can't recognise our patched JAR as 9.0.118 because
  one class file differs from the upstream SHA1; it falls back to
  filename + intra-image heuristics and downgrades the detection to
  7.0.86. See `.trivyignore` header comment and `tomcat/README.md` for
  the long-form explanation.

### Root-cause investigation (Phase 2B's silent-exit fingerprint)

The original Phase 2B attempt (Plan 2B in `docs/PHASE-2-ROADMAP.md`)
hit a silent JVM exit between `[SslService] SSL Keystore initialized`
and the embedded Tomcat `Bootstrap.init()` call. `error.log` had
nothing; no `hs_err_*.log`. The investigation needed `DEBUG=1
UFV_DAEMONIZE=false` to add `-debug -nodetach` to the jsvc invocation
so the JVM's stderr surfaced in docker logs (the daemonised default
sends stderr to `/dev/null`). With that capture the failure was
immediate and obvious:

```
java.lang.NoSuchMethodError:
  org.apache.catalina.startup.Bootstrap.setCatalinaBase(Ljava/lang/String;)V
    at com.ubnt.common.oOOO.A.<init>(Unknown Source)
    at com.ubnt.common.oOOO.A$$FastClassByGuice$$2d4252c8.newInstance
    ...
```

Static analysis of Tomcat 9.0.118's Bootstrap confirmed the diagnosis:
the `setCatalinaBase(String)` and `setCatalinaHome(String)` instance
methods that existed in Tomcat 7.0.86 were **removed** in Tomcat 9
(the catalina.base/home values are now read from system properties or
`user.dir` by the class's static initialiser at class-load time).
Tomcat 8.5.100 also has the methods removed (the API change was
backported), so no 8.5.x version offers a drop-in replacement.

The shim adds the two methods back as one-line wrappers around
`System.setProperty()`. The actual catalina.base / catalina.home
values used by Tomcat 9 still come from the static initialiser --
which reads them at class-load time, BEFORE airvision's setCatalina*
shim calls fire. Because the unifi-video init script `cd`'s to
`/usr/lib/unifi-video` before exec'ing jsvc, `user.dir` already
equals the value airvision would otherwise have passed to
`setCatalinaBase` -- so end-to-end behaviour is preserved.

### Verification

Built locally, image `uv-test:p2b`:

```
$ trivy image --severity HIGH,CRITICAL --ignore-unfixed --vuln-type os,library \
    uv-test:p2b --ignorefile .trivyignore
Total: 2 (CRITICAL: 0, HIGH: 2)
```

Down from `Total: 18 (CRITICAL: 5, HIGH: 13)` on `v3.10.13-10` and
`Total: 85 (CRITICAL: 34, HIGH: 51)` on `v3.10.13-7`.

Smoke test against a populated prod-data snapshot (725 MB DB, real
NVR instance, schema v3.10.13):

- container reached `(healthy)` status in ~90s (slightly slower than
  v3.10.13-10's 60s, likely due to Tomcat 9's expanded
  `LifecycleListener` set initialising; once initialised request
  latency is indistinguishable)
- MongoDB fCV probe: already 4.4, no migration
- `GET /` -> 200 (login UI renders)
- `GET /api/2.0/status` -> 200, JSON: `serviceState=READY`
- `GET /api/2.0/bootstrap` -> 200, JSON with live instance's
  `nvrName`, full `systemInfo{}` -- served through Tomcat 9's Coyote
  HTTP processor + Jersey 1.19 + jackson-databind 2.12.7.2 +
  Mongojack 2.7.0 against MongoDB 4.4 with WiredTiger storage
- `POST /api/2.0/login` with bogus credentials -> 403 JSON:
  `{"rc":"error","message":"api.err.BadUsernamePassword",...}` --
  exercises Tomcat 9's request-parsing path AND the full Jackson +
  jbcrypt + sanitizer chain
- Zero `ClassFormatError|NoSuchMethodError|LinkageError|NoClassDefFoundError|AbstractMethodError|IncompatibleClassChange|InvalidDefinitionException`
  across any log file
- Zero `tomcat` / `catalina` errors in `server.log` traceable to
  the bump (the pre-existing httpclient AWS-ALB cookie-parse warning
  and the UBNT-cloud analytics phone-home timeout remain unchanged)

Patched JAR sanity-check (built into the `tomcat-patcher` stage's
`RUN` block, fails the image build if any check fails):

- `org/apache/catalina/util/ServerInfo.properties` inside the
  patched JAR contains `server.info=Apache Tomcat/9.0.118` (proves
  we didn't accidentally degrade the JAR to 7.0.86)
- `javap -p Bootstrap.class` shows BOTH
  `public void setCatalinaBase(java.lang.String)` and
  `public void setCatalinaHome(java.lang.String)` (proves the patch
  was applied)

### Known residuals (2 total HIGH/CRITICAL CVEs after this release)

| Package | Installed | Count | Phase | Why deferred |
|---|---|---:|---|---|
| `jackson-core` | 2.12.7 | 1 | 2A.1 / 3 | CVE-2025-52999 needs jackson-core 2.15+. The 2.15 jackson-core JAR may itself be Java 8 compatible (untested); a follow-up Phase 2A.1 could bump jackson-core in isolation if so. Otherwise this is Phase 3 (Java 8 pin removal). |
| `owasp-java-html-sanitizer` | 20240325.1 | 1 | **3** | CVE-2025-66021 fixed in 20260101.1 but that release compiles to Java 10 bytecode (class major 54). Structurally blocked by the Java 8 pin. |

### Upgrade notes

Pure JAR-content change inside the same on-disk filenames. No data
migration. `docker pull && docker compose up -d` is sufficient.

Image-build time grows by ~30 seconds due to the new `tomcat-patcher`
stage (downloads `eclipse-temurin:8-jdk` -- ~450 MB -- on first build;
cached on subsequent builds).

Cumulative CVE reduction since v3.10.13-7 (5 releases):

| Image | CRITICAL | HIGH | Total | vs. v3.10.13-7 |
|---|---:|---:|---:|---:|
| `v3.10.13-7` (baseline) | 34 | 51 | 85 | -- |
| `v3.10.13-8` (Phase 0+1) | 13 | 43 | 56 | -34% |
| `v3.10.13-9` (Phase 1B) | 11 | 37 | 48 | -44% |
| `v3.10.13-10` (Phase 2A) | 5 | 13 | 18 | -79% |
| **`v3.10.13-11` (Phase 2B)** | **0** | **2** | **2** | **-98%** |

---

## [v3.10.13-10] -- JAR hardening Phase 2A: Jackson 2.7.x -> 2.12.x lockstep

### TL;DR

Bumps `jackson-databind`, `jackson-core`, and `jackson-annotations` in
lockstep to **2.12.7.2 / 2.12.7 / 2.12.7**, superseding the v3.10.13-8
Phase 1 bump to 2.7.9.x. **Closes all 30 of the jackson-databind CVEs
that the 2.7.9.x bump left behind.** Total Trivy HIGH/CRITICAL count
drops from **48 to 18** (-30 from v3.10.13-9; -67 from the v3.10.13-7
baseline, -79%), with CRITICAL count down from **11 to 5**
(-6 from v3.10.13-9; -29 cumulative from v3.10.13-7, -85%).

This release is the deliverable of Phase 2A, defined in
`docs/PHASE-2-ROADMAP.md` (added in `harden/phase2`). The roadmap's
central empirical question -- *"will Mongojack 2.7.0 (which airvision
pins because it uses the legacy MongoDB `DB*` API) tolerate Jackson
2.12.x at the bytecode-ABI level?"* -- was answered with an end-to-end
smoke test against a populated production MongoDB snapshot. The answer
is **yes**.

### Changed

- **`jackson-databind-2.7.4.jar`** (filename retained per
  `airvision.jar`'s pinned Class-Path): content **2.7.4 -> 2.12.7.2**.
  Closes ALL 52 of the original jackson-databind CVEs.

  The 30 CVEs that 2.7.9.7 could not close (the polymorphic
  deserialization gadgets that required the 2.10+ rework plus the
  2022 GraalVM-related fixes): CVE-2019-14540, CVE-2019-14892,
  CVE-2019-16335, CVE-2019-16942, CVE-2019-16943, CVE-2019-17267,
  CVE-2019-17531, CVE-2020-10650, CVE-2020-10673, CVE-2020-24616,
  CVE-2020-24750, CVE-2020-25649, CVE-2020-35490, CVE-2020-35491,
  CVE-2020-35728, CVE-2020-36179, CVE-2020-36180, CVE-2020-36181,
  CVE-2020-36182, CVE-2020-36183, CVE-2020-36184, CVE-2020-36185,
  CVE-2020-36186, CVE-2020-36187, CVE-2020-36188, CVE-2020-36189,
  CVE-2020-36518, CVE-2021-20190, CVE-2022-42003, CVE-2022-42004.

- **`jackson-core-2.7.4.jar`** (filename retained): content
  **2.7.4 -> 2.12.7**. Lockstep with databind. CVE-2025-52999
  (introduced after Jackson 2.12.x branch closed) is NOT closed --
  the fix is in jackson-core 2.15+; remaining residual.

- **`jackson-annotations-2.7.2.jar`** (filename retained): content
  **2.7.2 -> 2.12.7**. New addition relative to v3.10.13-9 -- previously
  this file came from the .deb at version 2.7.2 and was implicitly
  trusted to be Jackson-2.7-compatible. Jackson does not support
  mixed-minor combinations across `core` / `databind` / `annotations`
  at runtime, so the annotations bump is required by the lockstep.

The Phase 1 commit's CHANGELOG entry (v3.10.13-8) describes the 2.7.9.x
Jackson bump; in this release the Dockerfile installs 2.12.x bytes
into the same on-disk filenames, **superseding** the 2.7.9.x install.
The Dockerfile fetcher stage now downloads the 2.12.x JARs instead of
the 2.7.9.x JARs, and `checksums/SHA256SUMS` reflects the new digests.

### Why this works (the empirical answer)

Mongojack 2.7.0's bytecode was analysed (`docs/PHASE-2-ROADMAP.md` --
Phase 2A section) and its Jackson API surface enumerated. It uses
Jackson's
public APIs (`ObjectMapper`, `JsonSerializer`, `JsonDeserializer`,
`JavaType`, `TypeFactory`) AND a substantial set of
internal-package APIs (`databind.deser.BeanDeserializer`,
`databind.ser.BeanSerializerBase`, `databind.ser.DefaultSerializerProvider`,
`databind.util.TokenBuffer`, `databind.deser.SettableBeanProperty`,
`databind.introspect.Annotated`, etc.). Across Jackson 2.7 -> 2.12 the
PUBLIC methods of those internal classes that Mongojack 2.7.0 calls
have remained stable enough for runtime ABI compatibility, verified
empirically below.

airvision's polymorphism surface (the other classic Jackson-2.10+ risk
vector) is two classes (`UserGroup`, `Event`), both using the safe
`@JsonTypeInfo(use=Id.NAME)` + explicit `@JsonSubTypes` allowlist
pattern. There is no use of `enableDefaultTyping()` (the dangerous
pattern that Jackson 2.10+ fenced behind a `PolymorphicTypeValidator`).

### Verification

Built locally, image `uv-test:p2a`:

```
$ trivy image --severity HIGH,CRITICAL --ignore-unfixed --vuln-type os,library \
    uv-test:p2a --ignorefile .trivyignore
Total: 18 (CRITICAL: 5, HIGH: 13)
```

Down from `Total: 48 (CRITICAL: 11, HIGH: 37)` on `v3.10.13-9` and
`Total: 85 (CRITICAL: 34, HIGH: 51)` on `v3.10.13-7`.

Smoke test against a populated prod-data snapshot (725 MB DB, real
NVR instance, schema v3.10.13):

- container reached `(healthy)` status in 60s (identical to v3.10.13-9)
- MongoDB fCV probe: already 4.4, no migration
- `GET /` -> 200 (login UI renders)
- `GET /api/2.0/status` -> 200, JSON: `{"data":[{"serviceState":"READY","serviceName":"Discovery Service",...}], ...}`
- `GET /api/2.0/bootstrap` -> 200, JSON with the live instance's
  `nvrName`, full `systemInfo{}`, etc., round-tripped through
  jackson-databind 2.12.7.2 reading from a Mongojack 2.7.0
  `JacksonDBCollection` over MongoDB 4.4 with WiredTiger storage
- `POST /api/2.0/login` with bogus credentials -> 403 JSON:
  `{"rc":"error","message":"api.err.BadUsernamePassword",...}` --
  exercises Jackson deserialize (request body), the bcrypt password
  check, the json-sanitizer/owasp-html-sanitizer chain on user input,
  and Jackson serialize (error response)
- Zero `ClassFormatError|NoSuchMethodError|LinkageError|NoClassDefFoundError|AbstractMethodError|IncompatibleClassChange|InvalidDefinitionException`
  across any log file
- Zero entries in any log mentioning `jackson`, `Mongojack`,
  `fasterxml`, or `mongojack` in an ERROR / Exception / FATAL context

Class major versions of the bumped JARs (must be <= 52 for Java 8):

- jackson-databind-2.12.7.2: 51 (Java 7)
- jackson-core-2.12.7: 50 (Java 6)
- jackson-annotations-2.12.7: 50 (Java 6)

Maven Central SHA1s verified against `.sha1` files alongside each JAR:

- `93f380701400ae503ad0ac3e174e22ec7f1d789a  jackson-databind-2.12.7.2.jar`
- `04669a54b799c105572aa8de2a1ae0fe64a17745  jackson-core-2.12.7.jar`
- `2042461b754cd65ab2dd74a9f19f442b54625f19  jackson-annotations-2.12.7.jar`

SHA256s added to `checksums/SHA256SUMS` and enforced by the fetcher stage.

### Known residuals (18 total HIGH/CRITICAL CVEs after this release)

| Package | Installed | Count | Phase | Why deferred |
|---|---|---:|---|---|
| `tomcat-embed-core` | 7.0.86 | 16 | **2B** | Tomcat 9.0.118 in-place swap was attempted (see `docs/PHASE-2-ROADMAP.md` Plan 2B) and failed at JVM init with a silent exit between SSL keystore initialisation and the embedded Tomcat `Bootstrap.init()` call. Root-cause investigation deferred (needs `jsvc -debug -nodetach` to surface stderr; airvision's `/usr/sbin/unifi-video` daemonizes jsvc with stderr -> /dev/null). |
| `jackson-core` | 2.12.7 | 1 | 2A.1 | CVE-2025-52999 needs jackson-core 2.15+. jackson-core ABI (`JsonParser`, `JsonGenerator`, `Base64Variant`, `JsonStreamContext`) has been stable since Jackson 2.0; a 2.12 -> 2.15 jackson-core-only bump (without touching databind) is the recommended low-risk follow-up. |
| `owasp-java-html-sanitizer` | 20240325.1 | 1 | **3** | CVE-2025-66021 fixed in 20260101.1 but that release compiles to Java 10 bytecode (class major 54); the airvision-pinned 8u265 JVM rejects with `UnsupportedClassVersionError`. Structurally blocked by the Java 8 pin; Phase 3 unblocks it. |

### Upgrade notes

Pure JAR-content change inside the same on-disk filenames. No data
migration. `docker pull && docker compose up -d` is sufficient.

Cumulative CVE reduction since v3.10.13-7 (4 releases):

| Image | CRITICAL | HIGH | Total | vs. v3.10.13-7 |
|---|---:|---:|---:|---:|
| `v3.10.13-7` (baseline) | 34 | 51 | 85 | -- |
| `v3.10.13-8` (Phase 0+1) | 13 | 43 | 56 | -34% |
| `v3.10.13-9` (Phase 1B) | 11 | 37 | 48 | -44% |
| **`v3.10.13-10` (Phase 2A)** | **5** | **13** | **18** | **-79%** |

---

## [v3.10.13-9] -- JAR hardening Phase 1B: four smoke-tested transitive bumps

### TL;DR

Adds the four library bumps that needed real request-path smoke testing
before they could ship: jettison, commons-beanutils, json-sanitizer, and
owasp-java-html-sanitizer. Total Trivy HIGH/CRITICAL count drops from
**56 to 48** (-8 from v3.10.13-8; -37 from v3.10.13-7), with CRITICAL
count down from **13 to 11** (-2 from v3.10.13-8; -23 cumulative from v3.10.13-7).

Verified end-to-end against a populated production MongoDB snapshot
(725 MB, real NVR instance, v3.10.13 schema) -- the full
HTTP request stack including Jersey 1.19, Jackson 2.7.9.7, Tomcat 7.0.86,
jettison 1.5.4, and the sanitizer chain serves `/api/2.0/bootstrap`,
`/api/2.0/login`, and `/api/2.0/status` correctly with no
`ClassFormatError`, `NoSuchMethodError`, `LinkageError`, or any other
bump-attributable runtime issue in any of the 489k log lines emitted
during the smoke test.

### Changed

- **Four library bumps**, in-place content swap (filename unchanged):

  | JAR (filename retained) | Old content | New content | CVEs closed |
  |---|---|---|---|
  | `commons-beanutils.jar` | 1.7.0 | **1.11.0** | CVE-2019-10086, CVE-2025-48734 |
  | `jettison-1.1.jar` | 1.1 | **1.5.4** | CVE-2022-40150, CVE-2022-45685, CVE-2022-45693, CVE-2023-1436 |
  | `json-sanitizer-1.1.jar` | 1.1 | **1.2.3** | CVE-2021-23899, CVE-2021-23900 |
  | `owasp-java-html-sanitizer-r239.jar` | r239 | **20240325.1** | CVE-2021-42575 |

  Maven Central SHAs verified against published `.sha1` files. SHA256
  added to `checksums/SHA256SUMS` and enforced by the fetcher stage.

  All four are class major version 52 (Java 8 compatible), required by
  the airvision-pinned 8u265 JVM.

### Known residual: owasp-java-html-sanitizer CVE-2025-66021

A newer CVE-2025-66021 (HIGH, sanitizer XSS) was disclosed against
20240325.1 with a fix shipping in 20260101.1. We **cannot** take that
fix because 20260101.1 is compiled to Java 10 bytecode (class major
version 54) which our pinned 8u265 JVM rejects with
`UnsupportedClassVersionError`. This residual is structurally tied to
the airvision Java 8 pin (which is itself tied to the spec-illegal
identifiers in airvision.jar) and can only be closed by a Phase 3
airvision identifier rewrite.

### Verification

Built locally, image `uv-test:phase1b`:

```
$ trivy image --severity HIGH,CRITICAL --ignore-unfixed --vuln-type os,library uv-test:phase1b
Total: 48 (CRITICAL: 11, HIGH: 37)
```

Down from `Total: 85 (CRITICAL: 34, HIGH: 51)` on `v3.10.13-7` and
`Total: 56 (CRITICAL: 13, HIGH: 43)` on `v3.10.13-8`.

Smoke test against a populated prod-data snapshot (725 MB DB, instance
schema v3.10.13):

- container reached `(healthy)` status in 60s
- MongoDB fCV probe: already 4.4, no migration needed (mongod 4.4 came
  up clean on the existing WiredTiger storage)
- `GET https://localhost:7443/` -> 200 (login UI renders)
- `GET /api/2.0/status` -> 200, JSON: `{"data":[{"serviceState":"READY","serviceName":"Discovery Service",...}], ...}`
- `GET /api/2.0/bootstrap` -> 200, JSON includes `"nvrName":"...","systemInfo":{"version":"3.10.13","platform":"Ubuntu24.04",...}` -- real instance data round-tripped through jackson-databind 2.7.9.7
- `GET /api/2.0/camera` -> 401 (auth required, expected)
- `POST /api/2.0/login` with bogus credentials -> 403 `{"rc":"error","message":"api.err.BadUsernamePassword",...}` -- exercises Jackson deserialization of the request body, jbcrypt password check, json-sanitizer 1.2.3 on user input, and Jackson serialization of the error response

The two non-bump-related WARNs/ERRORs observed in `server.log` (httpclient
4.5.1 misparsing a valid AWS-ALB cookie `Expires` attribute, and the UBNT
analytics phone-home reporting a TCP read timeout) are pre-existing in
`v3.10.13-7` and not caused by this release.

### Upgrade notes

Pure JAR-content change inside the same on-disk filenames. No data
migration. `docker pull && docker compose up -d` is sufficient.

---

## [v3.10.13-8] -- JAR hardening Phase 0+1: inventory, log4j cleanup, four CVE bumps

### TL;DR

Phase 0 (audit) + Phase 1 (zero-risk bumps) of the JAR hardening plan land
together. The image now ships `docs/JAR-INVENTORY.md` as the source of truth
for every bundled `.jar` and its CVE exposure, and four libraries get
in-place version bumps. Total Trivy HIGH/CRITICAL count drops from **85 to
56** (-29), with CRITICAL count down from **34 to 13** (-21).

The Dockerfile changes are constrained by a property of `airvision.jar`
discovered during the audit: its `Manifest Class-Path` attribute pins every
dependency by **exact filename**, so JAR replacements must preserve the
on-disk filename even when the bytes inside change minor versions. The
existing v3.10.13-3 log4j step already worked this way (puts 2.17.2 bytes
into `log4j-core-2.1.jar`); this release applies the same pattern to four
more libraries.

### Added

- **`docs/JAR-INVENTORY.md`** -- full enumeration of all 82 bundled JARs
  with Maven coordinates, the 85 fixable HIGH/CRITICAL CVEs grouped by
  package, per-JAR native-library audit (all 82 are pure bytecode; the 4
  `.so` files are siblings in `lib/`, not bundled), `airvision.jar`
  obfuscation fingerprint (only ~1% of classes are obfuscated; static
  analysis in a future Phase 2 is feasible), and the documented
  `airvision.jar` Class-Path filename-pinning constraint. Includes a
  copy-pastable reproduction script for any subsequent maintainer.

### Changed

- **Dropped `--backup` from the existing log4j install step.** The
  v3.10.13-3 step used `install --backup`, which left
  `log4j-{api,core,slf4j-impl}-2.1.jar~` files in `/usr/lib/unifi-video/lib/`
  containing the genuine log4j 2.1 bytes. The JVM ignores `.jar~` (not on
  the classpath), but Trivy fingerprints them and a future filesystem-write
  attacker could rename one back. A defensive `rm -f *.jar~` is added in
  the same RUN layer for belt-and-braces.

- **Four library bumps**, in-place content swap (filename unchanged):

  | JAR (filename retained) | Old content | New content | CVEs closed |
  |---|---|---|---|
  | `commons-collections.jar` | 3.2 | **3.2.2** | CVE-2015-6420, CVE-2015-7501 (Apache Commons Collections deserialization gadget; adds Properties whitelist on `InvokerTransformer`) |
  | `commons-io-2.6.jar` | 2.6 | **2.18.0** | CVE-2024-47554 (XmlStreamReader CPU exhaustion) |
  | `jackson-core-2.7.4.jar` | 2.7.4 | **2.7.9** | (none directly; kept in lockstep with databind to avoid mixed-minor Jackson) |
  | `jackson-databind-2.7.4.jar` | 2.7.4 | **2.7.9.7** | 22 of 52: CVE-2017-7525, CVE-2017-15095, CVE-2017-17485, CVE-2018-5968, CVE-2018-7489, CVE-2018-11307, CVE-2018-12022, CVE-2018-12023, CVE-2018-14718, CVE-2018-14719, CVE-2018-14720, CVE-2018-14721, CVE-2018-19360, CVE-2018-19361, CVE-2018-19362, CVE-2019-12086, CVE-2019-14379, CVE-2019-14439, CVE-2019-20330, CVE-2020-8840, CVE-2020-9547, CVE-2020-9548 |

  Maven Central SHAs verified against published `.sha1` files; SHA256
  added to `checksums/SHA256SUMS` and enforced by the fetcher stage.

- **`.trivyignore`** -- added the 4 filename-mismatch log4j-2.1 CVE
  entries (CVE-2017-5645, CVE-2021-44228, CVE-2021-45046, CVE-2021-45105)
  with comments pointing at the JAR `Implementation-Version: 2.17.2`
  content fingerprint. These were filename-derived false positives.

### Known residual HIGH/CRITICAL exposures (post-Phase 1)

These remain in the image and are tracked for Phase 2:

| Package | Installed | CVE count | Phase | Why deferred |
|---|---|---:|---|---|
| `jackson-databind` | 2.7.9.7 | 30 | 2 | Requires Jackson 2.12+ ; forces Mongojack 2.7.0 -> 2.12+ ; needs a call-site audit of every airvision `ObjectMapper` / Mongojack glue invocation. |
| `tomcat-embed-core` | 7.0.86 | 16 | 2 | Tomcat 7 -> 9 is a Servlet 3.0 -> 4.0 spec jump; needs call-site verification that airvision's `TomcatLifecycleListener` and any custom valves still compile/link. Tomcat 10+ requires `jakarta.*` namespace which Jersey 1.19 cannot use. |
| `jettison` | 1.1 | 4 | 1B (smoke-test required) | Jersey transitive; bumping requires a smoke-test of `/api/2.0/*` JSON<->XML negotiation. |
| `commons-beanutils` | 1.7.0 | 2 | 1B | json-lib / Jersey transitive; verifies clean only with a populated DB. |
| `json-sanitizer` | 1.1 | 2 | 1B | OWASP path; needs UI render smoke-test. |
| `owasp-java-html-sanitizer` | r239 | 1 | 1B | Same as json-sanitizer. |
| `jackson-core` | 2.7.9 | 1 | 2 | CVE-2025-52999 requires 2.15+, blocked by databind transition. |

Phase 1B (the four packages requiring smoke tests) lands once a prod
snapshot is reproducibly importable. Phase 2 begins after that with a
static-analysis audit of `airvision.jar`'s Mongojack and
Tomcat-lifecycle call sites.

### Verification

Built locally, image `uv-test:phase1`:

```
$ trivy image --severity HIGH,CRITICAL --ignore-unfixed --vuln-type os,library uv-test:phase1
Total: 56 (CRITICAL: 13, HIGH: 43)
```

Down from `Total: 85 (CRITICAL: 34, HIGH: 51)` on `v3.10.13-7`.

JVM smoke check (in container, AdoptOpenJDK 8u265):

```
$ java -jar /usr/lib/unifi-video/lib/airvision.jar
2026-05-24 03:42:56,958 main ERROR Unable to locate appender "ConsoleAppender" ...
$ echo $?
0
```

`Main` classloads cleanly through the swapped Jackson 2.7.9.7 / 2.7.9,
commons-collections 3.2.2, commons-io 2.18.0, and log4j 2.17.2 jars; no
`ClassFormatError`, `NoSuchMethodError`, or `NoClassDefFoundError`. The
appender warning is a separate log4j config issue unrelated to this change
and only surfaces with `java -jar` directly (no log config); the
production launcher path (`/usr/sbin/unifi-video` via jsvc) sets up the
log config and does not emit this warning.

### Upgrade notes

- Pure JAR-content change inside the same on-disk filenames. No data
  migration. `docker pull && docker compose up -d` is sufficient.
- If you maintain a fork that adds further `.jar~` to `lib/` for any
  reason, the new `rm -f *.jar~` step will remove them at build time.

---

## [v3.10.13-7] -- chown /usr/lib/unifi-video AFTER usermod, not just at build

### TL;DR

`v3.10.13-6` added a Dockerfile chown of `/usr/lib/unifi-video` to
`unifi-video:unifi-video` after the `.deb` install. That fixed nothing
in practice. `useradd -r unifi-video` at build time picks whatever
free system UID it picks (UID 999 on a fresh Ubuntu base image). The
build-time chown sets every file to **999:999** on disk. At runtime,
`run.sh`'s `usermod -o -u "${PUID}" unifi-video` then moves the
`unifi-video` *user* to PUID (typically 1026 or 1000 on Synology),
leaving every file on disk still owned by the now-orphaned UID 999
-- which `ls -la` prints as a raw numeric `999` because no named
user maps to it anymore. The JVM (running as the new
`unifi-video` = PUID) gets `Permission denied` on its first write
to `/usr/lib/unifi-video/conf/evostream/config.lua` and the
EvoStream stack never comes up.

Fix: add the chown to `run.sh` *after* `usermod`/`groupmod`. Now the
runtime PUID owns the application tree, matching the user the JVM
will actually run as.

### Fixed

- **EvoStream config still failing on `v3.10.13-6`** with the same
  `FileNotFoundException` symptom from `v3.10.13-5` even though the
  Dockerfile chown ran successfully. Diagnostic confirmation from
  inside a running `v3.10.13-6` container:
  ```
  root@unifi-video:/# ls -la /usr/lib/unifi-video/
  drwxr-xr-x 1   999   999  364 May 24 01:29 conf
  drwxr-xr-x 1   999   999  176 May 24 01:29 bin
  drwxr-xr-x 1   999   999 3772 May 24 01:29 lib
  ...
  ```
  The numeric `999` rather than a named user is the giveaway: the
  build-time `unifi-video` UID is no longer the runtime
  `unifi-video` UID (which is now PUID). Fix: a new
  `chown -R unifi-video:unifi-video /usr/lib/unifi-video` step in
  `run.sh`, placed immediately after the `usermod`/`groupmod` block
  and BEFORE the existing chown logic for `/var/lib/unifi-video`.
  This re-aligns on-disk ownership of the application tree to the
  runtime PUID on every container start, completing in a fraction
  of a second (the tree is a few hundred MB, mostly bundled JARs).

### Notes on the Dockerfile chown (kept from v3.10.13-6)

The build-time `chown -R unifi-video:unifi-video /usr/lib/unifi-video`
in the Dockerfile is retained as defense-in-depth even though `run.sh`
now re-asserts it at runtime. It still does useful work in the
PUID == 999 case (default `useradd -r` UID) where `usermod` is a
no-op and the build-time ownership already matches.

### Upgrade notes

- Existing `v3.10.13-6` (or `-5`) installs can either (a) upgrade to
  `v3.10.13-7` directly, or (b) manually run inside the container:
  ```
  chown -R unifi-video:unifi-video /usr/lib/unifi-video
  /usr/sbin/unifi-video restart
  ```
  Option (b) works because `unifi-video` (the *user*) has already
  been usermod'd to PUID by the time the container is up, so the
  manual chown picks up the right UID. It survives `docker restart`
  but is lost on container recreate (e.g. DSM "Update" pointing at
  a new image), so (a) is the durable fix.

### Why this took three releases to nail down

Bug archaeology, continued from `v3.10.13-6`:

- `v3.10.13-1` / `-2` / `-3`: JVM never started (`ClassFormatError`).
- `v3.10.13-4`: JVM never invoked (`mongod` EACCES → `run.sh` exit).
- `v3.10.13-5`: `mongod` fixed; JVM starts; EvoStream config write
  fails because `/usr/lib/unifi-video/conf/evostream/` is owned by
  root (the `.deb` postinst's chown sweep didn't reach it).
- `v3.10.13-6`: Dockerfile chowns the tree to `unifi-video:unifi-video`
  -- but only fixes the case where PUID matches the build-time UID
  (UID 999). For everyone else (any non-default PUID), files end up
  owned by an orphaned UID after `usermod`, and the symptom is
  identical to `-5`.
- `v3.10.13-7`: chowns at runtime in `run.sh` after `usermod`, so
  every PUID configuration ends up with `/usr/lib/unifi-video`
  correctly owned.

---

## [v3.10.13-6] -- chown /usr/lib/unifi-video at build time

### TL;DR

`v3.10.13-5`'s ownership fixes resolved the runtime `mongod` crash on
boot and let the UniFi Video JVM finally reach its own startup path,
which immediately surfaced a separate, much older bug: the EvoStream
config file (`/usr/lib/unifi-video/conf/evostream/config.lua`) lives
*inside* the image (not on the bind mount) and was owned by `root`
because the `unifi-video.deb` postinst's chown pass was unreliable
inside a non-interactive `dpkg -i`. UniFi Video's JVM, running as the
`unifi-video` user, could not regenerate the config and bailed out of
its `EmsManager` startup with `Cannot generate configuration file`.
Cameras were unreachable; the controller spammed
`EmsApiWebsocket not connected` every 30s. Fixed by an explicit
`chown -R unifi-video:unifi-video /usr/lib/unifi-video` in the
Dockerfile, immediately after the `.deb` install.

### Fixed

- **`Cannot generate configuration file` / `EmsApiWebsocket not
  connected` at JVM startup.** UniFi Video regenerates the EvoStream
  config (`/usr/lib/unifi-video/conf/evostream/config.lua`) on every
  service start. The JVM runs as `unifi-video`, but
  `/usr/lib/unifi-video/conf/evostream/` was owned by `root` --
  the bundled `.deb`'s postinst ran in a non-interactive `dpkg -i`
  context where its own chown sweep does not reliably cover every
  subdirectory. Symptom in `server.log`:
  ```
  ERROR [uv.ems.svc] Failed to write EMS config
    (/usr/lib/unifi-video/conf/evostream/config.lua) in main
  java.io.FileNotFoundException: ... (Permission denied)
  ERROR [uv.service] Failed to start service EmsManager -
    com.ubnt.common.oOOO.E: Media Streamer failed:
    Cannot generate configuration file
  ```
  followed by an indefinite loop of `Cannot send EMS CLI Request
  (EmsException) ... EmsApiWebsocket not connected` every 30
  seconds, and `HouseKeepingTask` timeouts in `StreamManagement
  Service`. The web UI loads but no camera streams work. Fixed by
  appending `chown -R unifi-video:unifi-video /usr/lib/unifi-video`
  to the `dpkg -i unifi-video.deb` RUN layer in the Dockerfile,
  re-asserting the ownership that the postinst should have set.

### Why this surfaced only now

Bugs are interesting when one is masked by another. The sequence
back through the patch chain:

- `v3.10.13-1` / `-2` / `-3`: the JRE was Temurin 8u492 (HotSpot) or
  Semeru 8u482 (OpenJ9). Both reject UniFi Video's obfuscated
  `airvision.jar` at class-parse time with `ClassFormatError`. The
  JVM never started, so it never tried to write the EvoStream
  config, so this bug was invisible.
- `v3.10.13-4`: swapped to AdoptOpenJDK 8u265 (pre-strict-parser
  HotSpot). The JVM could now load `airvision.jar`, but `mongod`
  crashed first with the `WiredTiger.turtle` permission-denied
  error, `run.sh` exited at the `mongod failed` branch, and the
  JVM was never invoked.
- `v3.10.13-5`: fixed the `mongod` permission errors (both the
  `run.sh` chown-guard scoping and `migrate-mongo.sh`'s root-spawn).
  `mongod` now starts cleanly, control flows on into
  `/usr/sbin/unifi-video start`, jsvc spawns the JVM as
  `unifi-video`, and EvoStream config write is the next thing
  attempted -- where it fails.

Each release uncovered the *next* layer's bug. With v3.10.13-6, the
last permission-related stop in the boot sequence is removed.

### Upgrade notes

- Existing `v3.10.13-5` installs can either (a) upgrade to
  `v3.10.13-6` directly, or (b) `docker exec` into the running
  container and run
  `chown -R unifi-video:unifi-video /usr/lib/unifi-video`, then
  restart the container. Either path works; (a) makes the fix
  persistent.
- No data dir changes. fCV remains at 4.4. No snapshot rollback
  is needed for this upgrade.

---

## [v3.10.13-5] -- repair MongoDB ownership skew end-to-end

### TL;DR

Two intertwined bugs in earlier releases caused the runtime `mongod`
to crash on boot with
`[posix_open_file]: db-wt/WiredTiger.turtle: handle-open: open:
Permission denied`:

1. **`migrate-mongo.sh` ran its probe `mongod` as root** (inherited
   from `run.sh` PID 1), which meant the WiredTiger journal /
   checkpoint files it touched -- even on the trivial "fCV is
   already 4.4, nothing to do" happy path -- were left owned by
   root. The runtime `mongod` (running as the `unifi-video` user)
   then could not read them.
2. **`run.sh` skipped *all* ownership repair when `perms.txt`
   existed**, so the only recovery from a foreign-UID write into
   `db-wt/` (host tooling, snapshot restore, a diagnostic sidecar
   container, or bug #1 above) was a manual delete of
   `/var/lib/unifi-video/perms.txt`.

Both are fixed. The probe `mongod` now runs as `unifi-video` from
the outset, an EXIT trap in `migrate-mongo.sh` reclaims ownership on
every code path (not just the happy-path end), and `run.sh`
unconditionally chowns the small critical paths on every boot.
`perms.txt` is now scoped only to `videos/` -- the only path big
enough to justify skipping a recursive chown.

### Fixed

- **`migrate-mongo.sh` probe `mongod` now starts as the `unifi-video`
  user, not root.** Previously the probe inherited root from
  `run.sh` PID 1, and on the "fCV is already 4.4" early-return path
  (the steady state after the first successful migration) it exited
  without ever running the trailing chown-back step. Files touched
  by the probe -- including WiredTiger metadata that journal-replays
  on startup -- ended up owned by root, and the subsequent runtime
  `mongod` (as `unifi-video`) failed with `Permission denied:
  WiredTiger.turtle`. Switching to `runuser -u unifi-video --` for
  the probe means there is no ownership skew to repair afterwards.
- **`migrate-mongo.sh` now reclaims ownership on every exit path
  via an `EXIT` trap**, not just at the very end of the happy path.
  Defense-in-depth for any future code path or external tooling that
  slips files into `DB_PATH` with the wrong UID mid-migration.
- **`run.sh` now repairs ownership of MongoDB metadata on every
  boot.** Previously, if anything outside the runtime container
  (host-side tooling, snapshot replication restore, a diagnostic
  sidecar container, manual `chown`) wrote into `db-wt/` with a
  different UID, the runtime `mongod` would crash with the same
  symptom and the only recovery was a manual delete of `perms.txt`.
  The chown pass for the small critical paths (`db-wt/`, `db/`,
  `logs/`, `backup/`, `snapshot/`, and the root-level config files)
  now runs unconditionally; only the `videos/` subtree is still
  guarded by `perms.txt` (which is what `perms.txt` was always
  meant to protect -- a multi-TB recursive chown on every boot).
  Total time impact on the unconditional pass is well under a
  second on the largest realistic deployments.

### Upgrade notes

- If you're broken with the "Permission denied: WiredTiger.turtle"
  symptom on `v3.10.13-4`, upgrade to `v3.10.13-5` directly. The
  first boot will repair ownership on its own (run.sh chown pass +
  migrate-mongo.sh EXIT trap + the probe `mongod` now running as
  `unifi-video`). No manual `perms.txt` deletion required, but
  doing so does no harm.
- If you're already healthy on `v3.10.13-4`, `v3.10.13-5` is still
  a strictly-better release: it removes a latent footgun where the
  next external write into `db-wt/` (e.g. restoring from a Synology
  snapshot, or running a diagnostic sidecar container) would crash
  the controller until you manually re-chowned.
- Old `perms.txt` files (with the previous "skip all chown" wording)
  remain functionally compatible; `run.sh` only checks for the
  file's existence, not its contents. The file is rewritten with
  updated explanatory text on first `videos/` chown.

### Changed

- `perms.txt` semantics narrowed: from "skip all chown" to "skip only
  the `videos/` subtree chown". Backwards compatible.
- Slight reorganization of `run.sh`'s chown block into two clearly
  named passes (`# Pass 1: ALWAYS chown the small critical paths`
  and `# Pass 2: one-time chown of videos/ (perms.txt-guarded)`)
  with a top-of-file comment block documenting the design.

### Investigation timeline

This release ships in close succession to `v3.10.13-4` because of
how the bugs interleaved. The original `v3.10.13-4` boot test on
real hardware surfaced the `Permission denied: WiredTiger.turtle`
symptom only AFTER:

1. A morning session of empirical JVM-rejects-airvision.jar
   diagnostics where multiple sidecar containers had been spun up
   against the same data dir (each writing as its own internal
   `unifi-video` UID of 999, distinct from the production PUID).
2. The user manually deleting `perms.txt` to unstick `v3.10.13-4`.
3. The runtime container starting, `run.sh` chowning everything to
   PUID, `migrate-mongo.sh` running and immediately re-corrupting
   WiredTiger metadata back to root ownership before exiting on
   the "fCV is already 4.4" branch.

The pre-existing `run.sh` chown-guard logic was masking
`migrate-mongo.sh`'s root-spawn bug whenever `perms.txt` was
present: `run.sh` never chowned, so the root-owned post-migration
files were not visible as a "skew" relative to anything else. As
soon as the `run.sh` chown started actually running each boot, the
`migrate-mongo.sh` corruption became immediately fatal.

---

## [v3.10.13-4] -- pin pre-strict-parser HotSpot, start mongod in run.sh

> **Historical footnote (added v3.10.13-13):** the AdoptOpenJDK 8u265
> pin this release introduced was **retired in v3.10.13-13**.  Phase 3
> introduced the `uv-patcher` runtime tool that auto-discovers and
> rewrites every spec-illegal identifier in `airvision.jar` at
> container start (174 class entries + 1608 method/field references
> in the v3.10.13 JAR; the original analysis here that counted "6
> classes" caught the obvious `com/ubnt/A/super/oOOO/` cluster but
> missed the larger scope in `com/ubnt/airvision/`), removing the
> constraint that required the pre-strict-parser JRE.  The image now ships
> Canonical OpenJDK 21 LTS (apt-installed; v3.10.13-13 initially shipped
> Temurin 21 from a tarball but Phase 3.1 of that release moved to apt).
> This entry is preserved for the empirical
> investigation it documents (Temurin 8u492 + Semeru 8u482 reject the
> original JAR; 8u265 accepts), which informed the v3.10.13-13 patcher
> design.

### TL;DR

UniFi Video's `airvision.jar` is obfuscated with method/class names that
violate JVM Spec section 4.2.2. All HotSpot OpenJDK 8 builds from
`8u272-b10` (October 2020) onward, **and all Eclipse OpenJ9 builds at
any version**, reject the JAR at parse time. The only JREs that load it
are pre-`8u272` HotSpot 8 builds. We now ship one, intentionally pinned.
Concurrently fixed a separate run-time bug where `run.sh` never started
the `mongod` that UniFi Video's JVM expects to connect to.

### Fixed

- **`java.lang.ClassFormatError: Illegal method name "new.super" in class
  com/ubnt/airvision/Main` on every container start.** Replaced Eclipse
  Temurin 8u492 with AdoptOpenJDK 8u265-b01 (last pre-strict-parser
  HotSpot 8 build, July 2020). UniFi Video's bundled `airvision.jar`
  contains methods named with literal `.` characters (e.g. `new.super`)
  and classes named with Java reserved words (`super`, `Object`,
  `String`, `interface`), which violate JVM Spec section 4.2.2's
  "unqualified name" rules. OpenJDK's HotSpot parser tightened its
  enforcement of these rules in `jdk8u272-b10` (Oct 2020) as part of
  CVE-2020-14803 / JDK-8246383. Every HotSpot build since then -- and
  every Eclipse OpenJ9 build ever, since OpenJ9 follows JVM Spec
  identically -- rejects this JAR with `ClassFormatError`. The
  rejection happens in the native `defineClass1` method, so neither
  `-Xverify:none` nor `-noverify` bypasses it (those flags only disable
  the bytecode *verifier*, which runs after parsing).
- **`run.sh` never started `mongod` for UniFi Video to connect to.**
  The previous logic assumed `/usr/sbin/unifi-video start` would spawn
  `mongod` itself; it does not. On the original Ubuntu host installs,
  a separate `systemd` unit (`mongodb-server-7441.service`) handled
  this with an `After=`/`Requires=` dependency from
  `unifi-video.service`. In a container with no `systemd`, `run.sh`
  has to do that step itself. `run.sh` now starts `mongod` between
  `migrate-mongo.sh` and `/usr/sbin/unifi-video start`, using
  UniFi Video's own config (`/usr/lib/unifi-video/conf/mongod-wt.conf`)
  with `--fork` so it only returns once 7441 is accepting connections.

### Changed

- `Dockerfile`:
  - JRE artifact swapped to
    `OpenJDK8U-jre_x64_linux_hotspot_8u265b01.tar.gz` from
    `github.com/AdoptOpenJDK/openjdk8-binaries`.
  - `JAVA_HOME` is now `/usr/lib/jvm/java-8-openjdk` (was
    `java-8-temurin`). The symlink target points at
    `jdk8u265-b01-jre`.
  - Header comment and inline comments updated with the empirical
    investigation that justifies this pin. Anyone reading the
    Dockerfile in the future will see *exactly* why bumping the JRE
    is not safe without separately patching `airvision.jar`.
- `checksums/SHA256SUMS`: Temurin 8u492 entry replaced with
  AdoptOpenJDK 8u265 entry
  (`9bce39f63d24626da75778f240294fa466a0ed117e32db798164621fe30b0723`).
- `openjdk-8-equivs.control`: bumped `Version:` to `999:equivs-stub`
  so future `apt-get install openjdk-8-jre-headless` over this stub
  will correctly replace it (rather than apt seeing matching
  upstream `8u492-1` version and reporting "already the newest
  version installed").
- `run.sh`:
  - New `stop_mongod()` function uses SIGTERM with a 30-second
    timeout, then SIGKILL, so WiredTiger gets a chance to flush
    checkpoints cleanly during shutdown.
  - `graceful_shutdown` (SIGTERM/SIGINT handler) now stops both
    UniFi Video and `mongod` in order.
  - Removed the now-misleading
    `Waiting for the bundled mongod (started by unifi-video)...`
    poll loop. The corresponding `mongo --port 7441 --eval ping`
    polling never returned `done` and was the false symptom that
    delayed diagnosis of the actual root cause.

### Notes -- WHY this JRE is pinned (read before "upgrading" it)

This is the only image component the monthly auto-rebuild **cannot**
advance to current CVE patches. Everything else (Ubuntu base, MongoDB
4.4.x, OpenSSL, libcurl, libssl1.1, glibc, log4j, etc.) is still
refreshed on every monthly cycle.

**Empirical evidence underlying the pin** (gathered while diagnosing
the broken `v3.10.13-3` deploy):

| JRE | Date | Result with `airvision.jar` |
|---|---|---|
| AdoptOpenJDK 8u265-b01 (HotSpot) | Jul 2020 | **Loads cleanly; full UniFi Video startup; web UI on :7443** |
| Eclipse Temurin 8u492-b09 (HotSpot) | Apr 2026 | `ClassFormatError: Illegal method name "new.super"` |
| IBM Semeru 8u482 OpenJ9 0.57.0 | Jan 2026 | `JVMCFRE002 method name is invalid` (J9's parser; same enforcement, different error code) |

**Why no JVM flag bypasses this**: the offending check lives in
HotSpot's C++ class-file parser (`share/classfile/classFileParser.cpp`,
function `verify_legal_method_name`). It's an unconditional structural
validation that runs *before* the bytecode verifier. `-Xverify:none`,
`-noverify`, `-XX:+RelaxAccessControlCheck`, and the various
`-XX:+UnlockDiagnosticVMOptions` flags all touch the verifier or
access checks, not the parser. No published flag (experimental or
otherwise) disables this parser check.

**Why every modern OpenJDK distro behaves identically**: Temurin,
Corretto, Zulu, Liberica, Microsoft Build, and Oracle JDK are all
built from the same upstream OpenJDK source. The parser check is in
the shared C++ code; no downstream distro patches it out because
doing so would violate the JCK / TCK and lose Java certification.

**Why every UniFi Video Docker project picks an old JRE**:
`pducharme` pins `openjdk-8-jre-headless=8u162-b12-1`. `exsilium`,
`mx-shift`, `mgcrea`, `puppetjoy`, `nunofgs`, `11notes`, `ti-mo`,
`emirisman`, `karrots`, `hedlund`, and every other UniFi Video Docker
image discoverable on GitHub make equivalent choices. We're not
alone in this tradeoff; we just document it explicitly.

**Bounded exploit surface**:
- The JVM only loads trusted Ubiquiti code from disk. It never
  receives untrusted bytecode over the network. Most pre-`8u272`
  CVEs (browser-applet sandbox escapes, untrusted-code RCEs) require
  the attacker to feed bytecode to the JVM, which can't happen here.
- UniFi Video itself is EOL. The application code has unpatched
  vulnerabilities of its own that no JRE upgrade fixes.
- The image runs as the `unifi-video` user, not root, after entrypoint
  initialization.
- Standard container-host mitigations (network isolation,
  user-namespace remapping, AppArmor / SELinux) all still apply.

**Plausible future hardening paths** (each one is a separate effort,
not blocking the v3.10.13-4 ship):

1. **Patch `airvision.jar`** to rename spec-illegal identifiers using
   ASM bytecode manipulation. Mechanical part is ~1 day's work; the
   risk is reflection / Guice / JNI / serialized-MongoDB-class-name
   breakage that requires source we don't have. If successful,
   unblocks shipping a current JRE.
2. **Reverse-engineer and recompile `airvision.jar` from upstream
   source** (which Ubiquiti never published). Practically impossible.
3. **Custom-build OpenJDK with the strict-name check disabled**.
   Possible but means maintaining a JRE fork through every monthly
   security update.
4. **Migrate off UniFi Video to UniFi Protect**. Ubiquiti's
   recommended path. Not what this container is for.

### Notes -- upgrade procedure from a broken v3.10.13-1/-2/-3 deploy

1. Stop the failing container.
2. **Delete** the bogus `/volume1/docker/unifi-video/db/` directory
   if you have one from a `v3.10.13-1` or `-2` boot (covered in v3
   notes; harmless if already done or never existed).
3. Take a fresh snapshot of `/volume1/docker/unifi-video/` -- the
   v3.10.13-3 → v3.10.13-4 upgrade does not run the fCV migration
   again (it's idempotent), but a snapshot is cheap insurance.
4. Import `v3.10.13-4`, point your container at it, start. Expected
   log sequence:
   ```
   [info] UMASK / PUID / PGID set
   [info] perms.txt present; skipping chown/chmod.
   [info] Checking MongoDB featureCompatibilityVersion...
   [migrate-mongo] mongod 4.4 (probe) accepting connections on port 7441.
   [migrate-mongo] mongod 4.4 reports current fCV='4.4'.
   [migrate-mongo] fCV is already 4.4; nothing to do.
   [migrate-mongo] mongod 4.4 (probe) stopped.
   Starting mongod... done (pid <N>).
   Starting unifi-video... done.
   ```
   Web UI on `:7443/` reachable ~30-60 seconds after
   `Starting unifi-video... done.`

---

## [v3.10.13-3] -- migrate-mongo points at db-wt, not db

### Fixed

- **`migrate-mongo.sh` was probing the wrong dbpath**
  (`/var/lib/unifi-video/db`) on existing UniFi Video 3.10.13
  installs. UniFi Video has used the WiredTiger storage engine with
  dbPath `/usr/lib/unifi-video/data/db-wt` -> `/var/lib/unifi-video/db-wt`
  for years; the legacy `db/` directory is a leftover from the long-
  defunct MMAPv1 era and is usually empty on real-world installs.
  Pointing mongod 4.4 at an empty `db/` silently initialised a *fresh*
  WiredTiger database with default fCV=4.4, after which migrate-mongo
  reported "fCV is already 4.4; nothing to do" and exited successfully.
  The user's actual data in `db-wt/` (at fCV=4.0 from the bundled mongod
  4.0.19) was never touched, never migrated. UniFi Video's JVM then
  spawned its own mongod 4.4 against `db-wt/`, mongod refused to open
  fCV=4.0 data with a 4.4 binary, and the controller crashed before
  Log4j initialised -- producing the "no server.log entries, no
  errors anywhere" failure mode that needed direct filesystem
  inspection to diagnose.
- Default `DB_PATH` in `migrate-mongo.sh` is now
  `/var/lib/unifi-video/db-wt`. Still overridable via the `DB_PATH`
  env var for atypical installs.
- Added a WiredTiger.turtle metadata-file sanity check: if `DB_PATH`
  has files but no `.turtle`, migrate-mongo bails with a descriptive
  error rather than letting mongod create a fresh DB on top.
- Added a post-migration `chown -R "${PUID}:${PGID}" "${DB_PATH}"`
  step so the journal/checkpoint files migrate-mongo writes as root
  remain accessible to UniFi Video's later-spawned mongod child
  (which runs as the unifi-video user).

### Notes

- **Upgrade path from a broken `v3.10.13-1` or `v3.10.13-2` deploy**:
  1. Stop the failing container.
  2. **Delete** the bogus `/volume1/docker/unifi-video/db/` directory
     created by the wrong-path probe (it contains a fresh empty DB at
     fCV=4.4 and is not used by anything). UniFi Video's real data in
     `db-wt/` is untouched and ready for proper migration.
  3. Import `v3.10.13-3`, point your container at it, start.
  4. Watch `logs/migrate-mongod.log` for the proper
     `Migration complete: fCV is now 4.4.` line (this time against
     `db-wt/`).
- No `.deb`, MongoDB binary, or library version changed between
  v3.10.13-2 and v3.10.13-3. Only `migrate-mongo.sh` was touched.

---

## [v3.10.13-2] -- /usr/bin/java symlink fix

### Fixed

- **Container fails to start with `readlink: missing operand` from
  `/usr/sbin/unifi-video`.** Ubiquiti's init script resets `PATH` to
  the standard Debian set and then runs
  `JAVA_HOME=$(readlink -f $(which java) | sed ...)`. The
  `openjdk-8-jre-headless` equivs stub introduced in `v3.10.13-1`
  ships zero files, so the canonical `/usr/bin/java` symlink that
  the real openjdk-8-jre-headless `.deb` would have created via
  `update-alternatives` was missing. `which java` returned empty,
  `readlink -f` errored with "missing operand", `JAVA_HOME` ended up
  unset, jsvc could not find the JVM, and the container crash-looped
  before reaching the `Starting unifi-video... done.` line.
- Dockerfile now registers Temurin with `update-alternatives` for
  both `java` and `keytool` after the tarball is extracted, restoring
  the standard `/usr/bin/java` entry that scripts expect.

### Notes

- No `.deb`, MongoDB binary, or library version changed in this
  release. The pinned-artifact SHA256SUMS file is unchanged. Only
  the Dockerfile (one extra `update-alternatives` invocation) and
  documentation were touched.
- Anyone who pulled `v3.10.13-1` should re-pull `:v3.10.13-2`
  (or `:latest`). No data-volume change is required -- the fCV
  migration in `migrate-mongo.sh` is idempotent.

---

## [v3.10.13-1] -- initial modernization fork

### Forked from

`pducharme/UniFi-Video-Controller` at upstream commit
[`7faeecc`](https://github.com/pducharme/UniFi-Video-Controller/commit/7faeecc6e7fa094f9e4e779ad8c910cc97ad3629).
Original commit history is preserved.

### Added

- **Pinned, supply-chain-verified multi-stage build.** All six
  third-party artifacts (UniFi Video deb, MongoDB 4.4/4.2 tarballs,
  Eclipse Temurin JRE 8 tarball, libssl1.1 deb, log4j 2.17.2 tarball)
  are SHA256-pinned in `checksums/SHA256SUMS` and verified in stage 1
  of the Dockerfile before stage 2 copies anything out. Any tampering
  fails the build immediately.
- **`migrate-mongo.sh`** -- one-shot, idempotent fCV stepper that walks
  existing 4.0 datasets through 4.2 -> 4.4. Required because MongoDB
  refuses to skip versions. Logs to `/var/log/unifi-video/migrate-mongod.log`.
- **`openjdk-8-equivs.control`** -- equivs stub that Provides:
  `openjdk-8-jre-headless` so the UniFi Video deb's hard dependency
  resolves without installing Ubuntu's now-removed openjdk-8 package.
- **GitHub Actions: `build.yml`** -- hadolint + buildx + Trivy on every
  PR, plus a weekly cron rebuild against the current pinned base image
  to catch upstream regressions early.
- **GitHub Actions: `release.yml`** -- on tag `v*`, builds linux/amd64,
  pushes to GHCR with provenance + SBOM attestations, generates an
  SPDX-JSON SBOM, compresses the image to `.tar.zst` and `.tar.gz`,
  and attaches all four artifacts plus a `SHA256SUMS.txt` to a GitHub
  Release.
- **GitHub Actions: `monthly-rebuild.yml`** -- creates and pushes a
  date-stamped tag (e.g. `v3.10.13-1.2026-06`) on the 1st of every
  month, then dispatches `release.yml` against it to absorb base-image
  security patches and Temurin updates without manual intervention.
- **`.github/dependabot.yml`** -- weekly watch on GitHub Actions
  versions and the `ubuntu:24.04` base image digest.
- **`.hadolint.yaml`** -- project-specific lint config; treats warnings
  as failures except for a small disabled-rules list documented inline.
- **`.dockerignore`** -- aggressive exclusion of non-build context
  (docs, compose, .github, VCS metadata, runtime mount dirs).
- **`.gitattributes`** -- enforces LF line endings for shell, YAML,
  Dockerfile, and `SHA256SUMS` regardless of host OS.
- **`LICENSE`** -- MIT, with dual copyright (pducharme upstream +
  conmilo modernization) and an inventory of which license governs each
  embedded third-party component.
- **`MIGRATION.md`** -- step-by-step DSM swap procedure with a
  mandatory pre-swap snapshot section.  (Moved to `docs/MIGRATION.md`
  post v3.10.13-13.)
- **`HEALTHCHECK`** in the Dockerfile, polling `https://localhost:7443/`
  with a 240s start period that covers cold start + fCV migration.
- **`tini`** as PID 1 for clean SIGTERM propagation to `jsvc` + the
  bundled `mongod`.
- **UDP port exposure**: `7004/udp` (UVC-Micro talkback) and
  `10001/udp` (camera discovery / Inform) added to the EXPOSE list and
  docker-compose.yaml. Several earlier forks omitted these.
- **`USE_HOST_TMPFS` / `USE_UNIFI_TMPFS`** env-var contract carried
  over from `vuhuy/unifi-video-docker` for compose compatibility.

### Changed

- **Base image**: `phusion/baseimage:0.11` (Ubuntu 18.04, EOL 2023) ->
  `ubuntu:24.04` pinned by manifest digest. Adds ~6 years of base
  security patches.
- **JRE**: Ubuntu's frozen `openjdk-8-jre-headless=8u162-b12-1` (Feb
  2018) -> Eclipse Temurin 8u492-b09 (May 2026), TCK-certified.
  Installed to `/usr/lib/jvm/java-8-temurin` and on PATH via
  `JAVA_HOME`.
- **MongoDB**: pinned 4.0.x via MongoDB's deprecated bionic apt repo ->
  pinned 4.4.29 tarball at `/opt/mongodb-4.4/`, plus 4.2.25 tarball at
  `/opt/mongodb-4.2/` for the one-time fCV stepper. The deprecated apt
  repo is no longer referenced.
- **libssl1.1**: now pinned explicitly to `1.1.1f-1ubuntu2.24` from the
  focal-security pool. Ubuntu 24.04 doesn't include OpenSSL 1.1 by
  default, but the bundled MongoDB 4.4 `mongod` binary (built on Ubuntu
  20.04, dynamically linked against `libssl.so.1.1` + `libcrypto.so.1.1`
  -- verified via `objdump -p .../mongod | grep NEEDED`) still needs it.
  The pin is permanent until we can move off MongoDB 4.4, and we can't,
  because (1) airvision.jar's bundled mongo-java-driver 2.14.2 can't
  speak MongoDB 5.0+'s required wire protocol (Phase 5/6 driver rewrite),
  AND (2) MongoDB 5.0+ requires AVX which the Apollo Lake deploy target
  doesn't have.  (Note: UV's own JNI .so files and the unifi-video.deb
  itself do NOT link against libssl/libcrypto -- earlier CHANGELOG
  revisions and the Dockerfile comment block incorrectly attributed
  the dependency to the JVM bindings.  Corrected in v3.10.13-16; the
  driver half of the upgrade-blocker was added in v3.10.13-20.)
- **log4j**: 2.17.0 -> 2.17.2. One micro-bump that closes
  CVE-2021-44832 in the JDBC Appender. SHA256-verified via Apache's
  published SHA512 chain.
- **`run.sh`**: dropped the 3.4 / 3.6 / 4.0 fCV ladder rungs (no longer
  reachable from our pinned 4.4 binary); now calls
  `/migrate-mongo.sh` once before `unifi-video start`. Tolerates SIGINT
  alongside SIGTERM. Uses `case` for the umask branching. Preserves the
  `perms.txt` first-start guard and the upstream issue #178 tmpfs
  watchdog loop verbatim.
- **`docker-compose.yaml`**: defaults to the GHCR image (`build: .` is
  commented out as the alternative). Full 11-port set. PUID/PGID/UMASK
  + TZ explicitly defaulted. Inline comments document each port.
- **`unifi-video.patch`**: unchanged from upstream. Restored verbatim
  to keep the fork's literal-fork lineage intact.

### Removed

- The bundled MongoDB apt repo configuration (key + sources.list entry)
  -- replaced by the tarball install.
- The runtime install of `openjdk-8-jre-headless=8u162-b12-1` from
  apt -- not available in 24.04. Replaced by the equivs stub + Temurin.
- `MAINTAINER` directive in the Dockerfile (deprecated). Replaced by
  OCI image labels (`org.opencontainers.image.authors` et al.).

### Security

- All third-party artifacts now SHA256-pinned.
- Trivy scans every PR for HIGH/CRITICAL CVEs and fails the build on
  findings (`ignore-unfixed: true` to avoid noise from packages with no
  upstream patch yet).
- SARIF results uploaded to the GitHub Security tab.
- SBOM (SPDX-JSON) attached to every release plus an in-image
  attestation.
- Build provenance attestations enabled via `docker/build-push-action`
  with `provenance: mode=max`.

### Distribution

- New: `ghcr.io/conmilo/unifi-video-controller:<tag>` (public, no login
  required for pulls).
- New: GitHub Release attachments `image.tar.zst` (~30% smaller),
  `image.tar.gz` (DSM-native), `image.spdx.json` (SBOM), and
  `SHA256SUMS.txt` (verifies the three above).
- Not distributed: Docker Hub. Existing references in upstream README
  no longer apply.

### Notes

This fork is the *initial* modernization. The next release will
either be a date-stamped monthly rebuild (no code changes, fresh base
layers) or a `-2` patch release if a security CVE or bug warrants it.

---

## Credits

- [pducharme](https://github.com/pducharme) -- original
  `UniFi-Video-Controller` project, MongoDB fCV ladder approach in
  `run.sh`, log4j patching technique.
- [vuhuy](https://github.com/vuhuy) -- `USE_HOST_TMPFS` /
  `USE_UNIFI_TMPFS` env-var contract, DSM-specific operational hints.
- [paulcarlucci](https://github.com/paulcarlucci) -- pducharme PR #203
  (log4j 2.17 bump), which this fork carries forward.
