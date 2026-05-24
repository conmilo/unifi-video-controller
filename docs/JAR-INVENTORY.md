# UniFi Video JAR inventory and CVE baseline

**Image scanned**: `ghcr.io/conmilo/unifi-video-controller:v3.10.13-7`
`sha256:595b32f5e0f7c49b1751b91601427a48b6f4c9a164bdf37b1d6ad43458fbe2a7`

**Scan date**: 2026-05-23

**Tooling**:

- **Trivy 0.70.0** — CVE scan, filter `--severity HIGH,CRITICAL --ignore-unfixed --vuln-type os,library`
- **`unzip -p` + manifest/`pom.properties` parsing** — per-JAR Maven coordinates and bundled-native audit
- **CFR 0.152** — reserved for Phase 2 decompile; not run yet

Raw scan artifacts live in `/root/uv-harden/work/` on the maintainer's hardening
workstation (`trivy-out/v3.10.13-7.{json,txt}`, `jar-coordinates.txt`,
`jar-natives.txt`, `jar-table.txt`, `airvision-fingerprint.txt`).

## Summary

| Metric | Value |
|---|---|
| Bundled `.jar` files in `/usr/lib/unifi-video/lib/` | **82** |
| Bundled `.jar~` install-backup files (stale 2014-era log4j 2.1 bytes) | **3** |
| Bundled native `.so` files (siblings, not inside JARs) | **4** |
| OS-package HIGH/CRITICAL CVEs (Ubuntu 24.04 base) | **0** |
| Java-library HIGH/CRITICAL CVEs reported by Trivy | **85** (51 HIGH, 34 CRITICAL) |
| Distinct vulnerable packages | **10** |
| False-positive CVEs (log4j filename mismatch — see below) | **4** |
| **Real fixable Java-library CVE count** | **~81** |

The base image (Ubuntu 24.04, 232 packages) currently has zero fixable
HIGH/CRITICAL CVEs. **All risk is concentrated in bundled JARs.**

## Phase 0 decision-gate verdict

Per the hardening plan, the count of fixable HIGH/CRITICAL CVEs gates further
work:

| CVE band | Plan action |
|---|---|
| `<5` | Simplify — skip Phase 2/3 entirely |
| `5–9` | Standard Phase 1 |
| `≥10` | Phase 2 (decompile + audit) warranted |

**Result: 81 fixable CVEs → full plan in scope.** Proceed to Phase 1 (safe
bumps + log4j cleanup), then Phase 2 (decompile-driven verification before
bumping shared-API libraries).

## Vulnerable packages — detail

Sorted by CVE count, descending. *Fixed version* shows the lowest upstream
release that closes every CVE in the row.

### 1. `com.fasterxml.jackson.core:jackson-databind` 2.7.4 — **52 CVEs**

**File**: `lib/jackson-databind-2.7.4.jar`

CVEs (selection): CVE-2017-7525, CVE-2017-15095, CVE-2017-17485, CVE-2018-5968,
CVE-2018-7489, CVE-2018-11307, CVE-2018-12022, CVE-2018-12023, CVE-2018-14718
through CVE-2018-14721, CVE-2018-19360 through CVE-2018-19362, CVE-2019-12086,
CVE-2019-14379, CVE-2019-14439, CVE-2019-14540, CVE-2019-14892, CVE-2019-16335,
CVE-2019-16942, CVE-2019-16943, CVE-2019-17267, CVE-2019-17531, CVE-2019-20330,
CVE-2020-8840, CVE-2020-9547, CVE-2020-9548, CVE-2020-10650, CVE-2020-10673,
CVE-2020-24616, CVE-2020-24750, CVE-2020-25649, CVE-2020-35490, CVE-2020-35491,
CVE-2020-35728, CVE-2020-36179 through CVE-2020-36189, CVE-2020-36518,
CVE-2021-20190, CVE-2022-42003, CVE-2022-42004.

**Family**: polymorphic deserialization gadget chains (the entire "Jackson
gadget" family from 2017–2022).

**Latest in same minor (2.7.x)**: **2.7.9.7** (released 2020-03; the
hardening patch series). The plan's earlier reference to "2.7.10" was
incorrect — Jackson never published a 2.7.10.
**Latest in same major (2.x)**: **2.19.0** (released 2025-04)

**Recommended action**: bump to **2.7.9.7** as the Phase 1 zero-risk move
(same minor, drop-in ABI). Per the Trivy fix-version breakdown this closes
**22 of the 52 CVEs**: CVE-2017-7525, CVE-2017-15095, CVE-2017-17485,
CVE-2018-5968, CVE-2018-7489, CVE-2018-11307, CVE-2018-12022, CVE-2018-12023,
CVE-2018-14718 through 14721, CVE-2018-19360 through 19362, CVE-2019-12086,
CVE-2019-14379, CVE-2019-14439, CVE-2019-20330, CVE-2020-8840, CVE-2020-9547,
CVE-2020-9548.

The remaining **30 CVEs require Jackson 2.12.x or newer**, which forces a
**Mongojack 2.7.0 → 2.12+** bump and a Jersey/Servlet stack alignment. That
work is **Phase 2** (decompile-driven audit of every airvision call site that
touches Mongojack's `ObjectMapper`/`MongoCollection` glue, plus the Jersey
1.19 JAX-RS provider).

### 2. `org.apache.tomcat.embed:tomcat-embed-core` 7.0.86 — **16 CVEs**

**File**: `lib/tomcat-embed-core.jar`

CVEs: CVE-2018-1336, CVE-2018-8014, CVE-2018-8034, CVE-2019-0232,
CVE-2019-12418, CVE-2019-17563, CVE-2020-1938 (Ghostcat), CVE-2020-9484,
CVE-2021-25329, plus six 2026-dated entries (CVE-2026-24880, CVE-2026-41284,
CVE-2026-41293, CVE-2026-42498, CVE-2026-43512, CVE-2026-43513, CVE-2026-43515).

**Family**: AJP, JSPi, secure-cookie, session-fixation, deserialization.

**Latest in same minor (7.0.x)**: **7.0.109** (Tomcat 7 EOL 2021-03)
**Latest in same major (still tomcat-embed)**: **9.0.118** (Tomcat 9, same
Servlet API namespace `javax.servlet`)
**Newer line**: 10.1.55 / 11.0.22 — require `jakarta.*` namespace; **out of
scope** without a major source-level refactor (jersey 1.19 uses `javax.*`).

**Recommended action**: **Phase 2 audit required** before any bump. Tomcat 7
to 9 is a Servlet 3.0 → 4.0 spec jump; needs decompile-driven verification
that airvision's lifecycle listener and any custom valves still compile/link.
Phase 1 candidate: just **document the exposure**; do *not* attempt the bump
without a smoke-test environment.

### 3. `org.apache.logging.log4j:log4j-core` reported as 2.1 — **4 CVEs (false positives)**

**File**: `lib/log4j-core-2.1.jar`

CVEs: CVE-2017-5645, CVE-2021-44228 (Log4Shell), CVE-2021-45046, CVE-2021-45105.

**Reality**: the JAR *content* is log4j 2.17.2 (verified — `MANIFEST.MF` and
`META-INF/maven/org.apache.logging.log4j/log4j-core/pom.properties` both say
`version=2.17.2`, file timestamps 2022-02-23). Trivy infers the 2.1 version
from the **filename** when both a filename-derived and content-derived version
are present, then attaches the 2.1 vulnerabilities. The Dockerfile installs
the 2.17.2 jar with `install --backup` over the original 2.1 file, which
preserves the original 2.1 filename and leaves a `*.jar~` backup of the
genuine 2.1 bytes.

**Affected JARs**: `log4j-api-2.1.jar`, `log4j-core-2.1.jar`,
`log4j-slf4j-impl-2.1.jar` (filename), plus their `*.jar~` backups
(actually 2.1 bytes — see `lib/log4j-*.jar~` section below).

**Recommended action**: **Phase 1, zero-risk**:

1. **Stop generating `.jar~` backups.** Drop `--backup` from `install` in
   the log4j Dockerfile step, and add a defensive
   `rm -f /usr/lib/unifi-video/lib/*.jar~`. Trivy will stop fingerprinting
   the genuine 2.1 bytes that previously lingered on disk.
2. **Suppress the 4 filename-mismatch CVEs in `.trivyignore`** with comments
   pointing to the content fingerprint (`Implementation-Version: 2.17.2` in
   the JAR's `MANIFEST.MF`). Renaming the JARs to `log4j-*-2.17.2.jar` is
   **not safe** — see "Constraint: airvision.jar Class-Path is pinned by
   filename" below.

Together the two changes eliminate every log4j-related Trivy report at zero
ABI risk.

### 4. `org.codehaus.jettison:jettison` 1.1 — **4 CVEs**

**File**: `lib/jettison-1.1.jar`

CVEs: CVE-2022-40150, CVE-2022-45685, CVE-2022-45693, CVE-2023-1436 (XML/JSON
parser DoS, stack overflow).

**Latest in same minor (1.1.x)**: none — 1.1 was the final 1.1 release
**Latest in same major (1.x)**: **1.5.4**

**Recommended action**: Phase 1 candidate — bump to **1.5.4**. Brought in
transitively via Jersey 1.19. ABI surface is small (one entry point per
direction). Low risk after a smoke check of `/api/2.0/server` JSON↔XML
negotiation, but the verification needs the prod data snapshot, so list as
Phase 1 *with* smoke test.

### 5. `commons-beanutils:commons-beanutils` 1.7.0 — **2 CVEs**

**File**: `lib/commons-beanutils.jar` (manifest reports `1.6`, embedded
`pom.properties` reports `1.7.0` — Trivy correctly used the embedded pom)

CVEs: CVE-2019-10086, CVE-2025-48734.

**Latest in same minor (1.x)**: **1.11.0**

**Recommended action**: Phase 1 — bump to **1.9.4** (the version that
introduced `suppressClassLevel` defaulting to true) or to **1.11.0**. Used by
`json-lib` and indirectly by Jersey. Phase 2 should `javap` for any explicit
`PropertyUtils.setProperty(..., "class.classLoader.…")` patterns that the new
defaults would break (rare).

### 6. `commons-collections:commons-collections` 3.2 — **2 CVEs**

**File**: `lib/commons-collections.jar`

CVEs: CVE-2015-6420, CVE-2015-7501 (the Apache Commons Collections gadget,
i.e. "JBoss/Jenkins Java deserialization RCE 2015").

**Latest in same minor (3.2.x)**: **3.2.2** (the explicit fix — adds
`Properties` whitelist on `InvokerTransformer`)
**Latest in same major (3.x)**: 3.2.2 is the last 3.x

**Recommended action**: Phase 1 zero-risk — drop-in **3.2.2**. Used by Guice
3.0 and jersey-guice. No API change.

### 7. `com.mikesamuel:json-sanitizer` 1.1 — **2 CVEs**

**File**: `lib/json-sanitizer-1.1.jar`

CVEs: CVE-2021-23899, CVE-2021-23900 (input crafted to produce JS-injection
in browsers that consume the sanitized output via `eval`).

**Latest**: **1.2.3**

**Recommended action**: Phase 1 — bump to **1.2.3**. Used by Guice-shipped
gson/owasp paths; tiny ABI.

### 8. `com.googlecode.owasp-java-html-sanitizer:owasp-java-html-sanitizer` r239 — **1 CVE**

**File**: `lib/owasp-java-html-sanitizer-r239.jar`

CVEs: CVE-2021-42575 (sanitizer-bypass for `<select>`/`<option>`).

**Latest**: **20240325.1** (re-versioned post-r239; semver-ish dates)

**Recommended action**: Phase 1 — bump to a current dated release.
Compatibility is generally good (additive policy builder), but flag in PR
because some old policy DSL methods are deprecated.

### 9. `commons-io:commons-io` 2.6 — **1 CVE**

**File**: `lib/commons-io-2.6.jar`

CVEs: CVE-2024-47554 (uncontrolled CPU in `XmlStreamReader`).

**Latest in same minor (2.6.x)**: 2.6 final
**Latest in same major (2.x)**: **2.18.0**

**Recommended action**: Phase 1 zero-risk — bump to **2.14.0** or newer.
2.x has been ABI-stable since 2.4. Trivial.

### 10. `com.fasterxml.jackson.core:jackson-core` 2.7.4 — **1 CVE**

**File**: `lib/jackson-core-2.7.4.jar`

CVEs: CVE-2025-52999 (nesting depth in `JsonParser`).

**Latest in same minor (2.7.x)**: **2.7.9** (released 2017-02; jackson-core
did not get the 2.7.9.x patch suffixes — those were databind-only)

**Recommended action**: bump in **lockstep with `jackson-databind`** to
**2.7.9** as the Phase 1 move. The fix for CVE-2025-52999 actually landed in
2.15+, so 2.7.9 does **not** close this CVE — but the upgrade is still
warranted to keep core and databind on the same minor (Jackson does not
guarantee compatibility across mismatched minors), and the residual is
documented for Phase 2.

## Constraint: airvision.jar Class-Path is pinned by filename (HISTORICAL)

> **Lifted in v3.10.13-13.**  The `uv-patcher` runtime tool introduced in
> Phase 3 rewrites airvision.jar's Manifest Class-Path attribute at
> container start, mapping each legacy filename to the modernized one
> (e.g. `commons-io-2.6.jar` -> `commons-io-2.18.0.jar`,
> `tomcat-embed-core.jar` -> `tomcat-embed-core-9.0.118.jar`).  The two
> vestigial `tomcat-embed-logging-*` JARs are dropped from the Class-Path
> entirely.  See `uv-patcher/src/main/resources/airvision-renames.json`'s
> `jarFilenameRenames` section for the full mapping (kept in lockstep
> with the Dockerfile install steps).
>
> The original constraint analysis below is preserved for context.

The application's main JAR carries a hard-coded `Class-Path:` manifest
attribute listing every dependency by **exact filename**:

```
Class-Path: airvision.jar Java-WebSocket-1.3.0-45-gf96ce50.jar
 annotations-api.jar aopalliance.jar asm-3.1.jar avutils.1.0.38.jar
 ... commons-collections.jar commons-io-2.6.jar ...
 jackson-core-2.7.4.jar jackson-databind-2.7.4.jar ...
 log4j-api-2.1.jar log4j-core-2.1.jar log4j-slf4j-impl-2.1.jar ...
```

(Full Class-Path is 35 lines in the manifest, generated by `yGuard Bytecode
Obfuscator 2.6` at UBNT build time.)

This means **any JAR-replacement strategy must preserve the original
filename**. We cannot rename `commons-io-2.6.jar` to `commons-io-2.18.0.jar`
on disk, even if the bytes inside it are 2.18.0. The JVM would log a warning
about the missing `commons-io-2.6.jar` and then `ClassNotFoundException` the
moment any class from that JAR is referenced — which happens at airvision
startup.

**Implication for every Phase 1+ bump**: the install pattern is
**in-place content replacement**, e.g.

```dockerfile
install -m 400 -o unifi-video -g unifi-video -T \
    /tmp/jackson-databind-2.7.9.7.jar \
    /usr/lib/unifi-video/lib/jackson-databind-2.7.4.jar
```

The on-disk filename stays `jackson-databind-2.7.4.jar`; the content is
2.7.9.7. This is exactly the pattern the existing v3.10.13-3 log4j step
already uses (it puts 2.17.2 bytes into `log4j-core-2.1.jar`). Trivy's
filename-derived heuristic then double-counts these JARs, producing the
filename-mismatch false positives discussed in the log4j section.

**This is what Phase 3 shipped** (v3.10.13-13).  `uv-patcher` rewrites the
Class-Path attribute at container start so the lib/ filenames can match the
content versions, and strips the per-entry SHA-1/MD5 digests in the same
pass.  See `uv-patcher/README.md` for the implementation and
`uv-patcher/src/main/resources/airvision-renames.json` for the committed
mapping.

## Native libraries (loaded by `System.loadLibrary`, not bundled in any JAR)

These sit **alongside** the JARs in `/usr/lib/unifi-video/lib/`:

| File | Size | Loaded by JAR | AVX/CPU sensitivity |
|---|---:|---|---|
| `libsigar-amd64-linux.so` | (host metrics — JNI for Sigar) | `sigar.jar` | low — kernel `/proc` reads |
| `libubnt_avutils_jni.so` | (FFmpeg JNI, UBNT-built) | `avutils.1.0.38.jar` | **high — FFmpeg build flags determine AVX/SSE; do not replace without UBNT-provided rebuild** |
| `libubnt_mp4parser_jni.so` | (MP4 muxer JNI, UBNT-built) | `mp4parser.1.0.38.jar` | medium |
| `libubnt_webrtc_jni.so` | (WebRTC JNI, UBNT-built) | `webrtc.1.0.38.jar` | medium |

**Key invariant**: every `.jar` in `lib/` is **pure bytecode** (verified —
zero bundled `.so`/`.dll`/`.dylib`/`.jnilib` entries across all 82 JARs).
This means JAR upgrades cannot introduce a worse AVX requirement *via the
JAR itself* — the native floor is fixed by the four `.so` files. Any future
plan to swap `avutils`/`mp4parser`/`webrtc` JARs must also ship a matching
`.so` from a known-AVX-safe build.

Reproduce:

```bash
for jar in /root/uv-harden/work/lib/*.jar; do
  unzip -l "$jar" 2>/dev/null | awk '{print $NF}' | grep -E '\.(so|dll|dylib|jnilib)$' \
    && echo "  ^^ in $jar"
done
# (no output expected — confirmed)
```

## Stale install backups: `lib/*.jar~`

The Dockerfile uses `install --backup` to swap log4j 2.1 for 2.17.2 in place.
This leaves three files behind in `/usr/lib/unifi-video/lib/`:

```
log4j-api-2.1.jar~        # genuine log4j 2.1 bytes, dated 2014-10-19
log4j-core-2.1.jar~       # genuine log4j 2.1 bytes, dated 2014-10-19
log4j-slf4j-impl-2.1.jar~ # genuine log4j 2.1 bytes, dated 2014-10-19
```

The JVM never loads `.jar~`, so these are not on the runtime classpath. They
*are*:

- on the filesystem, with the genuine 2.1 (Log4Shell-era) `Lookup` / `Jndi`
  classes intact;
- discoverable by Trivy as additional Java packages (contributing to the
  filename-mismatch false-positive count).

**Phase 1 fix**: append `rm -f /usr/lib/unifi-video/lib/*.jar~` after the
log4j install step, and rename the live `.jar`s to their actual `2.17.2`
filenames so Trivy stops double-counting.

## Vendored / unidentifiable JARs

These do not carry a Maven `pom.properties` and their manifests omit
`Implementation-Version`; they are likely UBNT internal or vendored:

| JAR | Notes |
|---|---|
| `Java-WebSocket-1.3.0-45-gf96ce50.jar` | git-describe naming → UBNT-patched fork of TooTallNate/Java-WebSocket 1.3.0 + 45 commits past tag `f96ce50`. **Phase 2 candidate**: decompile and compare against upstream 1.3.0…1.3.x to bound the patch surface. |
| `aopalliance.jar` | Single-class, single-version since 2004. Safe. |
| `av2-migrator.jar` | Internal: AV1→AV2 storage migration tool. Not on the request-handling path. |
| `avutils.1.0.38.jar` | UBNT FFmpeg JNI — paired with `libubnt_avutils_jni.so`. Frozen. |
| `imgscalr-lib-4.2.jar` | imgscalr 4.2 (version in filename); upstream is alive. Standard. |
| `javax.inject.jar` | The JSR-330 stub (no version). Safe. |
| `json-lib-2.4-jdk15.jar` | json-lib 2.4 (jdk15 classifier). Known dead upstream (last release 2010); replacement candidate, but no current CVE pressure. |
| `mp4parser.1.0.38.jar` | UBNT MP4 JNI — paired with `libubnt_mp4parser_jni.so`. Frozen. |
| `sigar.jar` | Hyperic Sigar 1.6.4 (last upstream 2010). Paired with `libsigar-amd64-linux.so`. Frozen pending replacement. |
| `webrtc.1.0.38.jar` | UBNT WebRTC JNI — paired with `libubnt_webrtc_jni.so`. Frozen. |
| `airvision.jar` | The main application. See airvision section below. |

## `airvision.jar` obfuscation fingerprint

| Subtree | Class count | % | Status |
|---|---:|---:|---|
| `com/ubnt/airvision/` | 811 | 82% | original symbols, decompiles cleanly |
| `com/ubnt/common/` | 141 | 14% | original symbols |
| `com/ubnt/av/` | 30 | 3% | original symbols |
| `com/ubnt/A/super/oOOO/` | 10 | 1% | **obfuscated** (class names: `Object`, `String`, `super`, `F`, `OoOO`, `o0OO` — reserved-word / mixed-case style consistent with Allatori or Zelix KlassMaster) |
| **total** | **990** | 100% | — |

Reflection-symbol density via `strings(1)` is **zero** across the whole JAR
for `Class.forName`, `Method.invoke`, `ServiceLoader.load`, `Field.set`,
`Constructor.newInstance`. This rules out *plaintext* reflection in the
non-obfuscated 99%, but the 10 obfuscated classes may still use reflection
via constant-pool references that don't surface in raw string scans. Defer
final determination to Phase 2 (`javap -p -c` over the obfuscated bundle and
inspect resolved method refs).

**Phase 2 implication**: the obfuscated bundle is small enough (~10 classes,
likely a licensing/registration shim or a vendored crypto library) that a
focused decompile and review fits inside Phase 2's standard time budget.

## Full inventory (all 82 JARs)

Schema:

```
JAR  |  size (B)  |  coordinates (group:artifact:version) or manifest fallback  |  source
```

`source` is `pom` when read from embedded `META-INF/maven/.../pom.properties`,
`manifest` when reconstructed from `MANIFEST.MF` (`Implementation-Title`,
`Implementation-Version`, or `Bundle-SymbolicName`/`Bundle-Version` as
fallback). `?` means the manifest did not carry a usable field.

| JAR | size | coordinates | source |
|---|---:|---|---|
| `Java-WebSocket-1.3.0-45-gf96ce50.jar` | 95466 | `?:?` (UBNT git-described fork) | manifest |
| `airvision.jar` | 1433064 | `unifi-video:?` | manifest |
| `annotations-api.jar` | 11424 | `javax.servlet:3.0.FR` | manifest |
| `aopalliance.jar` | 4467 | `?:?` (JSR-330-era, 1.0) | manifest |
| `asm-3.1.jar` | 43033 | `ASM:3.1` | manifest |
| `av2-migrator.jar` | 133448 | `?:?` (UBNT internal) | manifest |
| `avutils.1.0.38.jar` | 2185 | `?:?` (UBNT FFmpeg JNI) | manifest |
| `bcpkix-jdk15on-160.jar` | 796532 | `bcpkix:1.60.0.0` | manifest |
| `bcprov-ext-jdk15on-160.jar` | 4260066 | `bcprov-ext:1.60.0.0` | manifest |
| `bcprov-jdk15on-160.jar` | 4189874 | `bcprov:1.60.0` | manifest |
| `bctls-jdk15on-160.jar` | 465514 | `bctls:1.60.0.0` | manifest |
| `bson4jackson-2.7.0.jar` | 63459 | `de.undercouch.bson4jackson:2.7.0` | manifest |
| `commons-beanutils.jar` | 188671 | `commons-beanutils:commons-beanutils:1.7.0` (Trivy/pom); manifest says 1.6 | manifest |
| `commons-cli-1.2.jar` | 41123 | `commons-cli:commons-cli:1.2` | pom |
| `commons-codec-1.8.jar` | 263865 | `commons-codec:commons-codec:1.8` | pom |
| `commons-collections.jar` | 571259 | `commons-collections:commons-collections:3.2` | manifest |
| `commons-io-2.6.jar` | 214788 | `commons-io:commons-io:2.6` | pom |
| `commons-lang-2.6.jar` | 284220 | `commons-lang:commons-lang:2.6` | pom |
| `disruptor-3.3.2.jar` | 79474 | `com.lmax.disruptor:3.3.2` | manifest |
| `ecj-4.4.2.jar` | 2310271 | `org.eclipse.jdt.core.compiler.batch:3.10.2.v20150120-1634` | manifest |
| `encoder-1.2.jar` | 36484 | `org.owasp.encoder:encoder:1.2` | pom |
| `ezmorph-1.0.6.jar` | 86487 | `net.sf.ezmorph:ezmorph:1.0.6` | pom |
| `guava-14.0.1.jar` | 2189117 | `com.google.guava:guava:14.0.1` | pom |
| `guice-3.0.jar` | 710683 | `com.google.inject:3.0` | manifest |
| `guice-assistedinject-3.0.jar` | 36423 | `com.google.inject.assistedinject:3.0` | manifest |
| `guice-grapher-3.0.jar` | 51316 | `com.google.inject.grapher:3.0` | manifest |
| `guice-jmx-3.0.jar` | 8552 | `com.google.inject.tools.jmx:3.0` | manifest |
| `guice-jndi-3.0.jar` | 6740 | `com.google.inject.jndi:3.0` | manifest |
| `guice-multibindings-3.0.jar` | 33892 | `com.google.inject.multibindings:3.0` | manifest |
| `guice-persist-3.0.jar` | 27063 | `com.google.inject.persist:3.0` | manifest |
| `guice-servlet-3.0.jar` | 64443 | `com.google.inject.servlet:3.0` | manifest |
| `guice-spring-3.0.jar` | 8844 | `com.google.inject.spring:3.0` | manifest |
| `guice-struts2-plugin-3.0.jar` | 17260 | `com.google.inject.struts2:3.0` | manifest |
| `guice-throwingproviders-3.0.jar` | 23506 | `com.google.inject.throwingproviders:3.0` | manifest |
| `hamcrest-core-1.3.jar` | 45024 | `hamcrest-core:1.3` | manifest |
| `httpclient-4.5.1.jar` | 732765 | `org.apache.httpcomponents:httpclient:4.5.1` | pom |
| `httpcore-4.4.3.jar` | 326594 | `org.apache.httpcomponents:httpcore:4.4.3` | pom |
| `httpmime-4.5.1.jar` | 40698 | `org.apache.httpcomponents:httpmime:4.5.1` | pom |
| `imgscalr-lib-4.2.jar` | 226824 | `?:?` (version 4.2 in filename) | manifest |
| `jackson-annotations-2.7.2.jar` | 50909 | `com.fasterxml.jackson.core:jackson-annotations:2.7.2` | pom |
| `jackson-core-2.7.4.jar` | 253001 | `com.fasterxml.jackson.core:jackson-core:2.7.4` | pom |
| `jackson-databind-2.7.4.jar` | 1204187 | `com.fasterxml.jackson.core:jackson-databind:2.7.4` | pom |
| `jackson-jaxrs-base-2.7.3.jar` | 29948 | `com.fasterxml.jackson.jaxrs:jackson-jaxrs-base:2.7.3` | pom |
| `jackson-jaxrs-json-provider-2.7.3.jar` | 16774 | `com.fasterxml.jackson.jaxrs:jackson-jaxrs-json-provider:2.7.3` | pom |
| `jackson-module-jaxb-annotations-2.7.3.jar` | 34579 | `com.fasterxml.jackson.module:jackson-module-jaxb-annotations:2.7.3` | pom |
| `jai-imageio-core-1.3.0.jar` | 601101 | `com.github.jai-imageio:jai-imageio-core:1.3.0` | pom |
| `javax.inject.jar` | 2497 | `?:?` (JSR-330 stub) | manifest |
| `jbcrypt-0.3m.jar` | 17750 | `org.mindrot:jbcrypt:0.3m` | pom |
| `jcl-over-slf4j-1.7.10.jar` | 16617 | `org.slf4j:jcl-over-slf4j:1.7.10` | pom |
| `jersey-core-1.19.jar` | 436689 | `com.sun.jersey:jersey-core:1.19` | pom |
| `jersey-guice-1.19.jar` | 16151 | `com.sun.jersey.contribs:jersey-guice:1.19` | pom |
| `jersey-multipart-1.19.jar` | 53275 | `com.sun.jersey.contribs:jersey-multipart:1.19` | pom |
| `jersey-server-1.19.jar` | 702882 | `com.sun.jersey:jersey-server:1.19` | pom |
| `jersey-servlet-1.19.jar` | 128719 | `com.sun.jersey:jersey-servlet:1.19` | pom |
| `jettison-1.1.jar` | 67758 | `org.codehaus.jettison:jettison:1.1` | pom |
| `jmdns-3.4.1.jar` | 204950 | `javax.jmdns:jmdns:3.4.1` | pom |
| `joda-time-2.10.2.jar` | 642711 | `joda-time:joda-time:2.10.2` | pom |
| `json-lib-2.4-jdk15.jar` | 159123 | `?:?` (json-lib 2.4 jdk15 classifier) | manifest |
| `json-sanitizer-1.1.jar` | 19814 | `com.mikesamuel:json-sanitizer:1.1` | pom |
| `jsr311-api-1.1.1.jar` | 46367 | `javax.ws.rs:jsr311-api:1.1.1` | pom |
| `jul-to-slf4j-1.7.10.jar` | 4725 | `org.slf4j:jul-to-slf4j:1.7.10` | pom |
| `log4j-api-2.1.jar` | 302511 | `org.apache.logging.log4j:log4j-api:2.17.2` ⚠ filename mismatch | pom |
| `log4j-core-2.1.jar` | 1811090 | `org.apache.logging.log4j:log4j-core:2.17.2` ⚠ filename mismatch | pom |
| `log4j-slf4j-impl-2.1.jar` | 24248 | `org.apache.logging.log4j:log4j-slf4j-impl:2.17.2` ⚠ filename mismatch | pom |
| `mail.jar` | 494975 | `com.sun.mail:javax.mail:1.4.4` | pom |
| `mimepull-1.6.jar` | 39112 | `org.jvnet:mimepull:1.6` | pom |
| `mongo-java-driver-2.14.2.jar` | 613146 | `org.mongodb.mongo-java-driver:2.14.2.RELEASE` | manifest |
| `mongojack-2.7.0.jar` | 139910 | `org.mongojack:mongojack:2.7.0` | pom |
| `mp4parser.1.0.38.jar` | 42777 | `?:?` (UBNT MP4 JNI) | manifest |
| `owasp-java-html-sanitizer-r239.jar` | 127456 | `org.owasp.html.sanitizer:239` | manifest |
| `persistence-api-1.0.2.jar` | 53842 | `javax.persistence:1.0.2` | manifest |
| `sigar.jar` | 428580 | `?:?` (Hyperic Sigar 1.6.4) | manifest |
| `slf4j-api-1.7.10.jar` | 32119 | `org.slf4j:slf4j-api:1.7.10` | pom |
| `sshj-0.13.0.jar` | 375616 | `com.hierynomus.sshj:0.13.0` | manifest |
| `tomcat-dbcp.jar` | 234043 | `Apache Tomcat:7.0.86` | manifest |
| `tomcat-embed-core.jar` | 2720483 | `Apache Tomcat:7.0.86` | manifest |
| `tomcat-embed-el.jar` | 176506 | `Apache Tomcat:7.0.86` | manifest |
| `tomcat-embed-jasper.jar` | 684789 | `Apache Tomcat:7.0.86` | manifest |
| `tomcat-embed-logging-juli.jar` | 45465 | `Apache Tomcat:7.0.86` | manifest |
| `tomcat-embed-logging-log4j.jar` | 91506 | `Apache Tomcat:7.0.86` | manifest |
| `tomcat7-embed-websocket.jar` | 259339 | `Apache Tomcat:7.0.86` | manifest |
| `webrtc.1.0.38.jar` | 8197 | `?:?` (UBNT WebRTC JNI) | manifest |

## Reproducing this inventory

From a clean WSL/Linux host with `docker`, `trivy`, `unzip`, `jq`, `awk`:

```bash
docker pull ghcr.io/conmilo/unifi-video-controller:v3.10.13-7

# CVE scan -- JSON for tooling, table for humans
mkdir -p trivy-out
trivy image --severity HIGH,CRITICAL --ignore-unfixed --vuln-type os,library \
  --format json   --output trivy-out/v3.10.13-7.json \
  ghcr.io/conmilo/unifi-video-controller:v3.10.13-7
trivy image --severity HIGH,CRITICAL --ignore-unfixed --vuln-type os,library \
  --format table  --output trivy-out/v3.10.13-7.txt \
  ghcr.io/conmilo/unifi-video-controller:v3.10.13-7

# Extract /usr/lib/unifi-video to a work/ dir
docker create --name uv-inspect ghcr.io/conmilo/unifi-video-controller:v3.10.13-7
mkdir -p work/lib
docker cp uv-inspect:/usr/lib/unifi-video/lib/. work/lib/
docker rm uv-inspect

# Per-JAR coordinates
for jar in work/lib/*.jar; do
  name=$(basename "$jar")
  pom=$(unzip -l "$jar" 2>/dev/null | grep -E 'META-INF/maven/.*/pom\.properties' | awk '{print $4}' | head -1)
  if [ -n "$pom" ]; then
    gav=$(unzip -p "$jar" "$pom" 2>/dev/null | tr -d '\r' | grep -E '^(groupId|artifactId|version)=')
    echo "${name} | POM | $(echo "$gav" | tr '\n' '|')"
  else
    bsn=$(unzip -p "$jar" META-INF/MANIFEST.MF 2>/dev/null | tr -d '\r' \
      | grep -E '^(Bundle-SymbolicName|Implementation-Title|Bundle-Version|Implementation-Version):' | head -4)
    echo "${name} | MANIFEST | $(echo "$bsn" | tr '\n' '|')"
  fi
done

# Confirm no bundled natives in any JAR
for jar in work/lib/*.jar; do
  unzip -l "$jar" 2>/dev/null | awk '{print $NF}' \
    | grep -E '\.(so|dll|dylib|jnilib)$' \
    && echo "  ^^ in $jar"
done

# Summarise CVEs by package
jq -r '[.Results[]?|select(.Vulnerabilities)|.Vulnerabilities[]?]
  | group_by(.PkgName)
  | map({pkg:.[0].PkgName, ver:.[0].InstalledVersion, count:length,
         ids:([.[]|.VulnerabilityID]|sort|unique|join(","))})
  | sort_by(-.count)' trivy-out/v3.10.13-7.json
```
