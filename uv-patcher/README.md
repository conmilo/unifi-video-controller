# `uv-patcher/` -- runtime JAR rewriter

A self-contained ASM-based Java tool that runs at container startup and rewrites
exactly one Ubiquiti JAR in the running container's writable layer:

**`/usr/lib/unifi-video/lib/airvision.jar`** -- UniFi Video's main application
JAR.  Contains many obfuscated classes throughout `com/ubnt/A/super/oOOO/`
AND `com/ubnt/airvision/...` whose class names, package segments, method
names, and field names violate JVM Spec section 4.2.2 (unqualified-name)
and JLS reserved-word rules.  Specifically: 174 class entries and 1608
method/field references in the v3.10.13 JAR (e.g. class names
`super`/`Object`/`String`, package segments `super`/`class`/`return`/`new`,
method names containing literal `.` such as `new.super`, method/field
names that are reserved words like `new`/`void`/`return`/`int`).  HotSpot
8u272+ and every Eclipse OpenJ9 build reject the JAR at parse time with
`ClassFormatError`.  The patcher walks the JAR once, identifies every
spec-illegal identifier programmatically (no hand-curated allowlist),
then runs an ASM `ClassRemapper` pass that rewrites class names,
package paths, and method/field references in lockstep.

**Auto-discovery, not a static spec.**  Earlier Phase 3 drafts hand-
curated a list of exactly six classes the `airvision-fingerprint.txt`
analysis had identified.  The pre-push smoke build showed the real
scope was ~30x larger.  The current design scans the JAR and applies
`ReservedNames`'s deterministic escape rules to every identifier that
matches the illegal-name predicate, so future airvision obfuscation
pattern changes are handled without code or spec edits.

The same pass also runs `BootstrapCallSiteRewriter` over every class,
which rewrites airvision's two dangling Tomcat-9
`Bootstrap.setCatalinaBase(String)` and `Bootstrap.setCatalinaHome(String)`
call sites (in `com/ubnt/common/oOOO/A.<init>`) to the equivalent
`System.setProperty("catalina.{base,home}", arg)` calls.  Tomcat 9 removed
those instance methods (now static, no-arg, read from system properties
or `user.dir`); rewriting the caller is what lets us run airvision on a
pristine upstream `tomcat-embed-core-9.0.118.jar` from Maven Central
**without** patching Tomcat itself.

The Bootstrap call-site rewrite is, in practice, a no-op at the moment
airvision invokes it -- Tomcat's static initialiser has already read
`catalina.base` (or fallen back to `user.dir`, which the `unifi-video`
init script sets to `/usr/lib/unifi-video` by `cd`'ing there before
`exec`'ing jsvc).  We emit the equivalent `System.setProperty` call
anyway so a maintainer disassembling the patched class sees the
source-level intent of airvision's original code rather than an
unexplained pop.

## Why runtime, not build-time?

The project's distribution constraint is **"no proprietary Ubiquiti code shipped
in our artefacts."**  A build-time rewrite would bake modified airvision bytes
into a published image layer, which violates that constraint even if the
modification is just identifier renaming.  Applying the rewrite at container
start means:

- The image's `airvision.jar` is bit-identical to Ubiquiti's
  `dl.ubnt.com/firmwares/ufv/v3.10.13/unifi-video.Ubuntu18.04_amd64.v3.10.13.deb`
  payload, and matches Ubiquiti's published SHA256 (`5838c61...`).
- A `docker exec ... sha256sum /usr/lib/unifi-video/lib/airvision.jar` on a
  running container will NOT match Ubiquiti's published SHA256 -- the rewritten
  bytes have a different hash -- but that diff exists only inside the running
  container, never in any published artefact.
- A `docker pull` of the image's layer always retrieves the upstream bytes.

`tomcat-embed-core-9.0.118.jar` is shipped pristine from Maven Central and
**stays pristine** inside the running container too (since v3.10.13-15;
earlier the build also injected a Bootstrap compatibility shim into it).
Trivy fingerprints the JAR as upstream, no `.trivyignore` SHA1-mismatch
entries needed.

## Build

Builds in any modern JDK 21 with Maven 3.9+ (the `patcher-builder`
Dockerfile stage uses `eclipse-temurin:21-jdk` for the Maven build,
but the produced jar is plain Java-21 bytecode that runs on any
OpenJDK 21+ runtime -- including Canonical's `openjdk-21-jre-headless`
that the runtime image actually ships):

```bash
cd uv-patcher
mvn -B clean package
# -> target/uv-patcher.jar (~2 MB, ASM + Jackson shaded in)
```

The Dockerfile's `patcher-builder` stage runs this in CI / on every image
build; the final image then `COPY --from=patcher-builder`s the jar to
`/opt/uv-patcher/uv-patcher.jar`.

## Runtime invocation

`/run.sh` runs the patcher between the ownership-reassertion pass and the
MongoDB fCV migration, so by the time `jsvc` launches the JVM the rewritten
JAR is in place:

```bash
java -jar /opt/uv-patcher/uv-patcher.jar \
    --target  /usr/lib/unifi-video/lib/airvision.jar \
    --spec    /opt/uv-patcher/airvision-renames.json \
    --output  /tmp/airvision-patched.jar

install -m 400 -o unifi-video -g unifi-video -T \
    /tmp/airvision-patched.jar \
    /usr/lib/unifi-video/lib/airvision.jar
```

The patcher is **idempotent**: if the target JAR's `META-INF/MANIFEST.MF`
already carries a `Patched-By: uv-patcher ...` header (the patcher writes
that header on every successful run), it logs "already patched" and
exits 0 without writing.  Restart-safe by construction.

## Exit codes

- **0** -- patched (or already patched, no-op).
- **2** -- I/O error reading the target or writing the output.
- **3** -- invalid spec JSON.
- **4** -- bad CLI args (missing required flag, target/spec not a file).

Stderr emits a `discovered N class renames, M member renames` summary
followed by an `airvision rewrite complete: ... K Bootstrap.setCatalina*
call(s) rewritten ...` line on success so a reviewer can audit the exact
rewrite scope from `docker logs`.

## Reviewing the rewrite

The deterministic escape rules live in
[`ReservedNames.java`](src/main/java/com/conmilo/uvpatcher/ReservedNames.java).
A reviewer audits this file to understand exactly what gets rewritten:

- Class simple name reserved word / java.lang name -> `Z` + capitalised
  (e.g. `super` -> `ZSuper`, `Object` -> `ZObject`, `String` -> `ZString`)
- Package segment reserved word -> `Z` + lowercase
  (e.g. `super` -> `Zsuper`, `class` -> `Zclass`, `return` -> `Zreturn`)
- Method/field reserved word -> `z` + lowercase
  (e.g. `new` -> `znew`, `void` -> `zvoid`, `return` -> `zreturn`)
- Method/field name containing literal `.` -> `.` replaced with `_`
  (e.g. `new.super` -> `new_super`)
- `<init>` and `<clinit>` preserved verbatim (JVM-spec special names)

The Bootstrap call-site rewrite rules live in
[`BootstrapCallSiteRewriter.java`](src/main/java/com/conmilo/uvpatcher/BootstrapCallSiteRewriter.java).
The substitution is a single, locally-bounded `INVOKEVIRTUAL` ->
`SWAP; POP; LDC; SWAP; INVOKESTATIC; POP` swap; see the class-level
javadoc for the stack diagram.

The single JSON spec file encodes the per-pass configuration that is NOT
computable from bytecode alone:

- `src/main/resources/airvision-renames.json` -- lib/ filename renames
  for the Manifest Class-Path rewrite, Class-Path additions for JAXB /
  JAF, manifest action flags.

To inspect the patched JAR locally:

```bash
java -jar target/uv-patcher.jar \
    --target /path/to/airvision.jar \
    --spec   src/main/resources/airvision-renames.json \
    --output /tmp/airvision-patched.jar

# Patcher prints discovery + rewrite stats:
#   uv-patcher: discovered 174 class renames, 1608 member renames.
#   uv-patcher: airvision rewrite complete: 990 classes processed, ...

# Diff entry lists to see exactly which classes were renamed:
diff \
    <(unzip -l /path/to/airvision.jar      | awk '{print $4}' | sort) \
    <(unzip -l /tmp/airvision-patched.jar  | awk '{print $4}' | sort)

# Disassemble a rewritten class to verify identifiers:
unzip -p /tmp/airvision-patched.jar com/ubnt/A/Zsuper/oOOO/ZSuper.class > /tmp/zsuper.class
javap -p /tmp/zsuper.class

# Confirm the Patched-By header:
unzip -p /tmp/airvision-patched.jar META-INF/MANIFEST.MF | grep Patched-By
```

## Tests

```bash
mvn -B test
```

- `ReservedNamesTest` (16 tests): locks down the deterministic escape
  rules.  Any change to the escape behaviour requires explicit test
  updates.
- `ManifestRewriterTest` (9 tests): exercises the Class-Path token
  rewrite (rename / removal / whitespace-collapse), the new
  `appendClassPathEntries` helper (Phase 3.2 JAXB additions), the
  per-entry digest strip, and the Patched-By header insertion against
  synthetic Manifest objects.
- `RenameSpecTest` (1 test): loads the committed JSON spec and
  verifies the shape the rewriter depends on, including the Phase 3.2
  `jarFilenameAdditions` list and the `addLog4jConsoleAppender` flag.
- `Log4jConfigRewriterTest` (4 tests, Phase 3.2): synthetic Ubiquiti-
  shaped log4j2.json fixture gets the ConsoleAppender appended;
  idempotency contract (reference-equality on no-op); unexpected JSON
  shapes are no-op'd rather than mangled.
- `JarRewriterTest` (2 tests, Phase 3.3): preserves directory entries
  across rewrite (without this, Jersey 1.x's package scanner finds
  zero classes); META-INF/ directory survives the .SF/.RSA/.DSA filter.
- `BootstrapCallSiteRewriterTest` (5 tests, Phase 3.5 / v3.10.13-15):
  rewrites both `setCatalinaBase` and `setCatalinaHome` call sites;
  rewrite counter is accurate; second pass is a no-op; similar-but-
  unrelated `INVOKEVIRTUAL` calls pass through untouched; the rewritten
  class loads under the JVM bytecode verifier AND actually side-effects
  `System.setProperty` with the original argument when executed.

**37 tests total.**  No Ubiquiti bytecode in any test resource (the
`BootstrapCallSiteRewriterTest` fixture classes are synthesised in
memory via ASM).  Integration coverage (does the rewritten JAR actually
load on OpenJDK 21?) is delegated to the docker buildx smoke step,
where the patcher runs against the real airvision.jar at every
container start.

## Why ASM, not Javassist / ProGuard / etc.?

- **ASM** is the lowest-level + most stable Java bytecode library; ASM 9.7
  handles class file versions up to 65 (Java 21).  Its `ClassRemapper` and
  `Remapper` API map this rename problem directly.
- **Javassist** parses class bytecode into a higher-level model; the
  reserved-word identifiers in airvision (`new.super`, etc.) break its
  source-level grammar at parse time.
- **ProGuard** can rename identifiers but is targeted at obfuscation, not
  de-obfuscation; its `-keep` rule grammar doesn't compose well with "rename
  exactly these illegal names and nothing else."

## Future scope (deferred from Phase 3)

- Rewrite airvision's MongoDB DB* API call sites to the post-2.x driver
  API.  Would unblock Mongojack 4.x + mongo-java-driver 4.x.  Much larger
  effort (38 files); separate project.
- Add a `--verbose` flag that prints every individual class / member
  rename as it happens (currently the patcher prints only summary
  counts to keep boot logs compact).

## Shipped in earlier patch levels

- Rewrite airvision's `Bootstrap.setCatalinaBase` / `setCatalinaHome`
  call sites to `System.setProperty(...)` directly -- shipped in
  v3.10.13-15.  Retired the separate Tomcat Bootstrap shim and let
  `tomcat-embed-core-9.0.118.jar` stay bit-pristine in the running
  container.  See `BootstrapCallSiteRewriter.java`.
