# Changelog

All notable changes to this fork are documented here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versioning is
`v<UniFi Video version>-<our patch level>[.<YYYY-MM>]` (e.g.
`v3.10.13-1` for the initial modernization, `v3.10.13-1.2026-06` for a
monthly auto-rebuild without code changes).

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
`jackson-core` internals from CFR-decompile inspection).

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
  added methods. 450 lines (~400 of upstream Tomcat decompiled by CFR
  0.152, plus the two new methods and one hand-fix to a CFR variable-type
  erasure).

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

CFR decompile of Tomcat 9.0.118's Bootstrap confirmed the diagnosis:
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

Mongojack 2.7.0 was decompiled (`docs/PHASE-2-ROADMAP.md` -- Phase 2A
section) and its Jackson API surface enumerated. It uses Jackson's
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
  obfuscation fingerprint (only ~1% of classes are obfuscated; CFR
  decompile in a future Phase 2 is feasible), and the documented
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
| `jackson-databind` | 2.7.9.7 | 30 | 2 | Requires Jackson 2.12+ ; forces Mongojack 2.7.0 -> 2.12+ ; needs CFR audit of every airvision `ObjectMapper` / Mongojack glue call site. |
| `tomcat-embed-core` | 7.0.86 | 16 | 2 | Tomcat 7 -> 9 is a Servlet 3.0 -> 4.0 spec jump; needs decompile-driven verification that airvision's `TomcatLifecycleListener` and any custom valves still compile/link. Tomcat 10+ requires `jakarta.*` namespace which Jersey 1.19 cannot use. |
| `jettison` | 1.1 | 4 | 1B (smoke-test required) | Jersey transitive; bumping requires a smoke-test of `/api/2.0/*` JSON<->XML negotiation. |
| `commons-beanutils` | 1.7.0 | 2 | 1B | json-lib / Jersey transitive; verifies clean only with a populated DB. |
| `json-sanitizer` | 1.1 | 2 | 1B | OWASP path; needs UI render smoke-test. |
| `owasp-java-html-sanitizer` | r239 | 1 | 1B | Same as json-sanitizer. |
| `jackson-core` | 2.7.9 | 1 | 2 | CVE-2025-52999 requires 2.15+, blocked by databind transition. |

Phase 1B (the four packages requiring smoke tests) lands once a prod
snapshot is reproducibly importable. Phase 2 begins after that with a CFR
decompile of `airvision.jar`'s Mongojack and Tomcat-lifecycle call sites.

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
  because MongoDB 5.0+ requires AVX which the Apollo Lake deploy target
  doesn't have.  (Note: UV's own JNI .so files and the unifi-video.deb
  itself do NOT link against libssl/libcrypto -- earlier CHANGELOG
  revisions and the Dockerfile comment block incorrectly attributed
  the dependency to the JVM bindings.  Corrected in v3.10.13-16.)
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
