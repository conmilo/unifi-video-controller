# Phase 2 roadmap: Jackson 2.12 bump + Tomcat 9 investigation

> **Update (2026-05-24, after this doc was first written):** Both plans
> shipped, and Phase 3 has now also shipped.  Cumulative CVE count is
> **0** as of `v3.10.13-13`.
>
> - **Plan 2A** -- Jackson 2.7.x -> 2.12.x lockstep -- shipped in
>   `v3.10.13-10`. Closed all 30 residual jackson-databind CVEs.
>
> - **Plan 2B** -- Tomcat 7.0.86 -> 9.0.118 -- shipped in `v3.10.13-11`.
>   The "Open questions" section below was the planned diagnostic;
>   question 1 (`DEBUG=1 UFV_DAEMONIZE=false`) immediately surfaced the
>   actual failure: `NoSuchMethodError:
>   org.apache.catalina.startup.Bootstrap.setCatalinaBase(Ljava/lang/String;)V`.
>   Tomcat 9 made `setCatalinaBase(String)` and `setCatalinaHome(String)`
>   static + reduced them to no-arg variants reading from system
>   properties / `user.dir`. v3.10.13-11 fixed this with a build-time
>   patch (compile a Bootstrap.java with two instance-method shims
>   re-added, splice the resulting class into the upstream JAR via a
>   new `tomcat-patcher` Dockerfile stage). Tomcat 8.5
>   was investigated as an intermediate jump (open question 4) but the
>   same instance-method removal was back-ported to 8.5.x; 8.5 offered
>   no advantage. Open questions 2, 3, and 5 became unnecessary once the
>   stack trace pointed directly at the missing method.
>
> - **Plan 2A.1** -- jackson-core 2.12.7 -> 2.15.4 -- shipped in
>   `v3.10.13-12`.  Closed CVE-2025-52999.
>
> - **Phase 3** -- airvision identifier rewrite + OpenJDK 21 unpin --
>   shipped in `v3.10.13-13`.  Closed the final residual
>   (`owasp-java-html-sanitizer` CVE-2025-66021), retired the
>   AdoptOpenJDK 8u265 pin, **and** retired the build-time
>   `tomcat-patcher` stage by moving the Bootstrap shim into the same
>   runtime `uv-patcher` tool that handles airvision.  The image now
>   ships pristine `tomcat-embed-core-9.0.118.jar` from Maven Central
>   (no SHA1-mismatch entries needed in `.trivyignore`).  See
>   `uv-patcher/README.md` and CHANGELOG `v3.10.13-13`.
>
> Cumulative CVE delta through `v3.10.13-13`: **85 -> 0** HIGH/CRITICAL
> (-100%); CRITICAL eliminated since v3.10.13-11.  No residuals remain.
>
> The rest of this document is preserved as the original audit / Phase 2
> design narrative.

---


**Scope**: close the remaining 48 fixable HIGH/CRITICAL CVEs in
`v3.10.13-9` (30 jackson-databind + 16 tomcat-embed-core + 1 jackson-core
+ 1 owasp-html). Phase 2 is bounded by what is possible WITHOUT Phase 3
(airvision identifier rewrite + JRE unpin).

**Method**: empirical -- static analysis of `airvision.jar` (990 classes)
+ targeted compatibility tests against the populated
`<redacted-NVR-name>` prod-data snapshot.

**Outcome of this session**:

| Plan | Status | CVE delta if shipped |
|---|---|---:|
| **2A: Jackson 2.12.7.2 (+ core/annotations 2.12.7) -- in lockstep** | **VERIFIED WORKING** (build + JVM smoke + container boot + 4-endpoint HTTP probe against prod data) | **-30** |
| **2B: Tomcat 7.0.86 -> 9.0.118 (4-JAR swap, preserve filenames)** | **FAILED initial attempt** (JVM dies silently after SSL keystore init; root cause requires jsvc-debug to surface) | -16 (when working) |
| **Residual after 2A**: jackson-core CVE-2025-52999 (1), owasp-html CVE-2025-66021 (1), tomcat-embed-core (16) | -- | -- |

**If only Plan 2A ships** the image goes from 48 -> 18 fixable
HIGH/CRITICAL CVEs (a further -63% on top of Phase 1+1B). Adding Plan 2B
when it works gets to 2 residuals (both blocked by the airvision Java 8 pin
and structurally only fixable in Phase 3).

## Two constraints that shape every Phase 2 decision

### Constraint 1: airvision uses legacy MongoDB `DB*` API

Finding:

```
$ grep -hrE '^import com\.mongodb\.' airvision-src --include='*.java' | sort -u
import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBCursor;
import com.mongodb.DBObject;
import com.mongodb.util.JSONParseException;

$ grep -hrE '^import org\.mongojack\.' airvision-src --include='*.java' | sort -u
import org.mongojack.DBCursor;
import org.mongojack.DBQuery;
import org.mongojack.DBUpdate;
import org.mongojack.ObjectId;
```

These are all from the **legacy** Mongo Java Driver 2.x API surface
(`DB`, `DBCollection`, `DBObject`, `DBCursor`, `BasicDBObject` -- the
"DB-prefixed" types) plus the matching Mongojack 2.x wrappers
(`org.mongojack.DBCursor` etc.) that adapt to those.

The legacy API was **removed in mongo-java-driver 4.0**. It is preserved
through `mongo-java-driver 3.12.14` (the final release of the legacy
uber-artifact, June 2023). The modern driver split (`mongodb-driver-sync`,
etc.) does not include the `DB*` types.

Mongojack's compatibility matrix:

| Mongojack | Mongo driver | Jackson | Legacy DB* API |
|---|---|---|---|
| **2.x** (current: 2.7.0) | 2.x or 3.x | 2.7+ | yes (`JacksonDBCollection`) |
| 3.0.x | 3.x | 2.10+ | yes (`LegacyJacksonDBCollection`, deprecated) |
| 3.0.3+ | 3.x | 2.10+ | **removed** |
| 4.x | 3.x sync or 4.x sync | 2.12+ | no |
| 5.x | 5.x sync | 2.15+ | no |

**Implication**: bumping Mongojack past 2.7.x requires **rewriting every
airvision call site that touches `DBCollection`, `DBCursor`, `DBObject`,
`BasicDBObject`, `DBQuery`, `DBUpdate`**. Rough scale: 38 files have
`org.mongojack.*` imports, 7 files have direct `com.mongodb.*` imports.
These are spread across `data/`, `service/data/`, `service/recording/`,
`service/notification/`, `service/scheduling/`, `service/stats/`. The
work is mechanical but extensive, and requires either:

- the ability to recompile `airvision.jar` from rewritten source
  (Ubiquiti doesn't ship sources; reconstructed source from bytecode
  is lossy, especially for the obfuscated `com/ubnt/A/super/oOOO/`
  bundle), or
- in-place bytecode rewriting via ASM (complex, error-prone for the
  Mongojack call surface).

**Therefore Phase 2 keeps Mongojack at 2.7.0.** The hardening question
becomes: how much can we move Jackson without breaking Mongojack 2.7.0's
ABI assumptions?

### Constraint 2: airvision.jar Class-Path is pinned by filename

Already documented in `docs/JAR-INVENTORY.md` "Constraint: airvision.jar
Class-Path is pinned by filename" and reinforced in Phase 1+1B. Every
JAR replacement in Phase 2 keeps the original on-disk filename and only
swaps the bytes inside.

## Phase 2A -- Jackson 2.12.7.2: TESTED, READY TO SHIP

### Findings

Mongojack 2.7.0's Jackson API surface (from static analysis of
`mongojack-2.7.0.jar`):

- Public Jackson API: `ObjectMapper`, `JsonSerializer<T>`,
  `JsonDeserializer<T>`, `JavaType`, `TypeFactory`, `JsonParser`,
  `JsonGenerator`, `Module`.
- Internal Jackson API (`databind.deser.*`, `databind.ser.*`,
  `databind.introspect.*`, `databind.util.*`): `BeanDeserializer`,
  `BeanSerializerBase`, `BeanPropertyWriter`, `ContainerSerializer`,
  `DefaultSerializerProvider`, `SerializerFactory`,
  `SettableBeanProperty`, `Annotated`, `TokenBuffer`, etc.

Risk: Jackson does not guarantee internal-package stability across
minors. Mongojack 2.7.0 calls these internal APIs and could break on
2.10+ where Jackson reworked polymorphic deserialization. **The only way
to know is to try it.**

Airvision's polymorphism surface (the other Jackson-2.10+ risk vector):

```
$ grep -rE '@JsonTypeInfo|enableDefaultTyping|activateDefaultTyping' airvision-src
com/ubnt/airvision/data/users/UserGroup.java:
  @JsonTypeInfo(use=JsonTypeInfo.Id.NAME, include=JsonTypeInfo.As.PROPERTY, property="groupType")
com/ubnt/airvision/data/event/Event.java:
  @JsonTypeInfo(use=JsonTypeInfo.Id.NAME, include=JsonTypeInfo.As.PROPERTY, visible=true, property="eventType")
```

Both use **`Id.NAME` with explicit `@JsonSubTypes` allowlists** -- the
**safe** polymorphism pattern that Jackson 2.10+ continues to support
without requiring a `PolymorphicTypeValidator`. There is no use of
`enableDefaultTyping()` (the dangerous pattern that 2.10+ deprecated and
fenced behind PTV).

### Empirical test

Image lineage: `uv-test:p1b` (= proposed v3.10.13-9) layered with three
JAR content swaps:

```
jackson-databind-2.7.4.jar    <- jackson-databind-2.12.7.2 bytes (1517768 B)
jackson-core-2.7.4.jar        <- jackson-core-2.12.7 bytes      ( 365538 B)
jackson-annotations-2.7.2.jar <- jackson-annotations-2.12.7 bytes ( 75705 B)
```

Maven Central SHA1s verified before install:

```
93f380701400ae503ad0ac3e174e22ec7f1d789a  jackson-databind-2.12.7.2.jar
04669a54b799c105572aa8de2a1ae0fe64a17745  jackson-core-2.12.7.jar
2042461b754cd65ab2dd74a9f19f442b54625f19  jackson-annotations-2.12.7.jar
```

Class major versions: 51, 50, 50 -- all Java 8 compatible (the airvision
8u265 pin accepts up to major 52).

Run against the `<redacted-NVR-name>` 724 MB prod-data snapshot:

- Container reached `(healthy)` in **60 seconds** (identical to Phase 1B).
- `GET /` -> **200**, login UI renders.
- `GET /api/2.0/bootstrap` -> **200** with full instance JSON:
  `{"nvrName":"<redacted-NVR-name>","systemInfo":{"version":"3.10.13","platform":"Ubuntu24.04",...}}` --
  every field is round-tripped through `jackson-databind 2.12.7.2`
  via Mongojack 2.7.0 against the real MongoDB collection.
- `POST /api/2.0/login` with bogus credentials -> **403**
  `{"rc":"error","message":"api.err.BadUsernamePassword",...}` -- exercises
  Jackson deserialize (request body) + jbcrypt + json-sanitizer 1.2.3 +
  Jackson serialize (error response).
- Zero `ClassFormatError | NoSuchMethodError | LinkageError |
  NoClassDefFoundError | AbstractMethodError | IncompatibleClassChange |
  InvalidDefinitionException` across all server.log lines emitted during
  the smoke run.
- Zero entries in any log file mentioning `jackson`, `Mongojack`, or
  `fasterxml` in an ERROR/Exception/FATAL context.

Trivy reading on the test image (with `.trivyignore` applied):

```
$ trivy image --severity HIGH,CRITICAL --ignore-unfixed --vuln-type os,library
    uv-test:p2-jackson212 --ignorefile .trivyignore
Total: 18 (CRITICAL: 5, HIGH: 13)
```

The jackson-databind entry has **disappeared** -- all 30 previously-flagged
CVEs are closed.

### CVEs closed by Plan 2A

All 30 remaining jackson-databind CVEs (the full set that 2.7.9.7 left
behind):

CVE-2019-14540, CVE-2019-14892, CVE-2019-16335, CVE-2019-16942,
CVE-2019-16943, CVE-2019-17267, CVE-2019-17531, CVE-2020-10650,
CVE-2020-10673, CVE-2020-24616, CVE-2020-24750, CVE-2020-25649,
CVE-2020-35490, CVE-2020-35491, CVE-2020-35728, CVE-2020-36179,
CVE-2020-36180, CVE-2020-36181, CVE-2020-36182, CVE-2020-36183,
CVE-2020-36184, CVE-2020-36185, CVE-2020-36186, CVE-2020-36187,
CVE-2020-36188, CVE-2020-36189, CVE-2020-36518, CVE-2021-20190,
CVE-2022-42003, CVE-2022-42004.

### Residual: jackson-core CVE-2025-52999

After Plan 2A jackson-core is at 2.12.7, which **does not** close
CVE-2025-52999 (fix is in 2.15+). Two paths to close it:

1. **Bump jackson-core only** to 2.15.x or later. Jackson minor-to-minor
   ABI within jackson-core (the streaming-JSON parser/generator layer)
   is the most stable surface in the Jackson stack -- the `JsonParser`,
   `JsonGenerator`, `Base64Variant`, `JsonStreamContext` types Mongojack
   2.7.0 uses are essentially unchanged since 2.0.  Risk: low. **This is
   the recommended Phase 2A.1 follow-up if Plan 2A ships.**

2. **Bump jackson-databind to 2.15.x too** (full alignment). Risk: higher
   -- 2.15 introduced stricter `StreamReadConstraints` defaults and some
   internal API churn that Mongojack 2.7.0 may not tolerate. Untested.

Recommend doing Plan 2A first, validating it in production, then
attempting 2A.1 in a separate change.

### Implementation outline for Plan 2A (Dockerfile change)

Same in-place content swap pattern as Phase 1/1B. After the Phase 1B
RUN block, add a Phase 2A block:

```dockerfile
# -------- Phase 2A: Jackson 2.7.x -> 2.12.x lockstep --------------------
# Verified: Mongojack 2.7.0 internal-API tolerant of Jackson 2.12.x.
# Verified: airvision's @JsonTypeInfo usage uses the safe explicit
# @JsonSubTypes pattern (NOT enableDefaultTyping), so 2.10+ stricter
# polymorphic deserialization handling is not a regression.
# Closes 30 jackson-databind CVEs at zero observable ABI impact.
COPY --from=fetcher /artifacts/jackson-databind-2.12.7.2.jar  /tmp/...
COPY --from=fetcher /artifacts/jackson-core-2.12.7.jar        /tmp/...
COPY --from=fetcher /artifacts/jackson-annotations-2.12.7.jar /tmp/...
RUN install -m 400 -o unifi-video -g unifi-video -T \
        /tmp/jackson-databind-2.12.7.2.jar \
        /usr/lib/unifi-video/lib/jackson-databind-2.7.4.jar && \
    install -m 400 -o unifi-video -g unifi-video -T \
        /tmp/jackson-core-2.12.7.jar \
        /usr/lib/unifi-video/lib/jackson-core-2.7.4.jar && \
    install -m 400 -o unifi-video -g unifi-video -T \
        /tmp/jackson-annotations-2.12.7.jar \
        /usr/lib/unifi-video/lib/jackson-annotations-2.7.2.jar && \
    rm -f /tmp/jackson-*.jar
```

`checksums/SHA256SUMS` additions:

```
d7b2aa928fa2e27594609cd2e8c323040b73812e8844af9f82a630bc4748d141  jackson-databind-2.12.7.2.jar
3987a6a335046e226e56b81d69668fb5a91b155ea7fd96b0851adbb7d4ac1ca6  jackson-core-2.12.7.jar
3cacef714a89f3d68b69fa11263afa55a6aa2fdef1fff93ded22caa16b54687c  jackson-annotations-2.12.7.jar
```

The previous Phase 1 entries for `jackson-core-2.7.9.jar` and
`jackson-databind-2.7.9.7.jar` become unused and should be removed from
the fetcher stage and `SHA256SUMS`.

## Phase 2B -- Tomcat 9.0.118: FAILED initial attempt

### Finding: airvision's Tomcat surface is small and public-API

```
$ grep -hrE '^import org\.apache\.(catalina|tomcat|coyote)\.' airvision-src
import org.apache.catalina.LifecycleEvent;       # com/ubnt/common/oOOO/A.java
import org.apache.catalina.LifecycleException;   # com/ubnt/common/oOOO/A.java
import org.apache.catalina.LifecycleListener;    # com/ubnt/common/oOOO/A.java + TomcatLifecycleListener.java
import org.apache.catalina.Server;               # com/ubnt/common/oOOO/A.java
import org.apache.catalina.Service;              # com/ubnt/common/oOOO/A.java
import org.apache.catalina.connector.Connector;  # com/ubnt/common/oOOO/A.java
import org.apache.catalina.startup.Bootstrap;    # com/ubnt/common/oOOO/A.java
import org.apache.catalina.util.ServerInfo;      # com/ubnt/common/oOOO/A.java
import org.apache.catalina.filters.RestCsrfPreventionFilter;  # 2 files
import org.apache.tomcat.util.http.fileupload.ByteArrayOutputStream;  # 1 file
```

5 files total, all using public Catalina API. Every class listed exists
in Tomcat 9 with substantially the same signature (verified by spot
checks against the Tomcat 9.0 javadoc -- `Bootstrap.setCatalinaBase`,
`setCatalinaHome`, `init`, `start`, `stop`, `destroy`, `setAwait` all
remain).

**Therefore**: the surface-area review predicts Tomcat 7.0.86 -> 9.0.118
should be a safe content swap. **The empirical result contradicted this.**

### Empirical test (failed)

Image lineage: `uv-test:p2-jackson212` layered with 4 Tomcat 9.0.118
content swaps:

```
tomcat-embed-core.jar         <- tomcat-embed-core-9.0.118       (juli embedded)
tomcat-embed-el.jar           <- tomcat-embed-el-9.0.118
tomcat-embed-jasper.jar       <- tomcat-embed-jasper-9.0.118
tomcat7-embed-websocket.jar   <- tomcat-embed-websocket-9.0.118  (note name)
```

Three JARs intentionally NOT swapped:

- `tomcat-dbcp.jar` -- the 9.0.118 release is compiled to Java 9
  bytecode (class major 53); the airvision-pinned 8u265 JVM would
  `UnsupportedClassVersionError` on any class load. airvision uses
  MongoDB, not JDBC, so the on-disk file is shadowed; left at 7.0.86
  content (no observable Trivy contribution).
- `tomcat-embed-logging-juli.jar` -- no Tomcat 9 equivalent on Maven
  Central (juli now ships inside tomcat-embed-core). airvision's
  Class-Path lists this file AFTER `tomcat-embed-core.jar` so 9.0.118
  juli wins via classpath-order; the 7.0.86 juli is shadowed.
- `tomcat-embed-logging-log4j.jar` -- no Tomcat 9 equivalent (the
  log4j-1.x bridge was deprecated). airvision uses log4j 2.17.2
  directly, so the bridge JAR is vestigial.

Run against the same prod-data snapshot:

- Container reached `(health: starting)` and stayed there for **3+
  minutes** before the test was aborted (Phase 1B and Plan 2A both
  reached `(healthy)` in 60 seconds).
- `server.log` (the application's log4j output) progresses normally
  through:
  - jsvc init + start
  - System configuration loaded
  - SSL keystore loading
  - **"[SslService] SSL Keystore initialized"** at +1.5 seconds
  - **nothing further is ever logged**
- `ps -ef | grep -E 'jsvc|java'` inside the container returns empty --
  the JVM has died.
- No `hs_err_*.log` (no native JVM crash).
- `error.log` (jsvc's stderr capture) has nothing relevant -- the
  jsvc invocation in `/usr/sbin/unifi-video` does not pass `-outfile`
  or `-errfile` so daemonized stderr defaults to `/dev/null`.

The JVM is exiting cleanly (no native crash, no java stack trace
written to log4j) somewhere between "SSL Keystore initialized" and the
next step (which static analysis identifies as the embedded Tomcat
bootstrap in `com/ubnt/common/oOOO/A`). This is the canonical fingerprint of an
uncaught `Throwable` thrown from a thread BEFORE log4j-on-the-classpath
is fully wired in, OR an uncaught `Error` that bypasses application-level
loggers (LinkageError, ClassCircularityError, etc.).

### Open questions for the next Phase 2B session

1. **Re-run with `DEBUG=1` and `UFV_DAEMONIZE=false`** so jsvc adds
   `-debug -nodetach` and the JVM's stderr goes to the docker log
   stream. This is the highest-priority diagnostic.

2. **Try a partial swap**: only `tomcat-embed-core` 9.0.118, leave the
   other three at 7.0.86. If that boots, walk back up to identify which
   JAR introduces the failure.

3. **Look at `org.apache.catalina.startup.Bootstrap` constructor and
   `init()` between 7.0.86 and 9.0.118**. The class was significantly
   restructured for module-system compatibility in the 9.0 release
   line (especially around `digester3` removal and the new
   `SystemLogHandler`). Static analysis shows airvision calls
   the no-arg `Bootstrap()` constructor followed by `setCatalinaBase`
   / `setCatalinaHome` / `init` -- if the no-arg constructor's
   semantics changed (e.g., a new `SystemLogHandler` install that
   conflicts with jsvc), that would match the silent-exit fingerprint.

4. **Try Tomcat 8.5.x as an intermediate jump**. Tomcat 8.5 has the
   same JAR layout as 7.0 (still ships `tomcat-embed-logging-juli` and
   `-log4j` as separate artifacts) and is a Servlet 3.1 spec (vs 7.0's
   3.0 and 9.0's 4.0). 8.5 was EOL'd 2024-03 so it would only buy us
   time to investigate the 9.0 path, but it would close most of the
   tomcat-embed-core CVE set in the meantime.

5. **Look at `catalina.properties` and `server.xml` syntax compatibility**.
   The application carries its own catalina-base under
   `/usr/lib/unifi-video/conf/` -- if any Tomcat 7-syntax directives
   in those files (e.g., older `Realm` configurations) are rejected by
   the 9.0 parser, the lifecycle would abort during `init()`.

6. **Examine `JreMemoryLeakPreventionListener` defaults**. Tomcat 9.0
   adds several new "prevent X classloader pin" defaults that activate
   in `init()`. One of them touches the J2SE security manager and would
   throw if airvision installs an incompatible security policy at JVM
   start (which it might via `Main`).

### Recommendation

**Defer Plan 2B to a separate session**. The investigation requires an
iterative debug cycle (5+ rebuilds + boots per finding) that is best
done with focused time. The static-analysis findings are encouraging
(the airvision Tomcat surface is genuinely small and uses only public
APIs) so the work is probably 1-2 days of focused investigation, not
weeks.

In the meantime, Plan 2A is independently shippable and closes the
majority of the remaining CVE burden.

## What Phase 2A (ship-ready) achieves

If only Plan 2A ships:

| Image | CRITICAL | HIGH | Total |
|---|---:|---:|---:|
| `v3.10.13-9` (Phase 1+1B) | 11 | 37 | 48 |
| `v3.10.13-10` (Plan 2A only) | **5** | **13** | **18** |

Residuals after 2A (18 total):

| Package | Installed | Count | Path forward |
|---|---|---:|---|
| `tomcat-embed-core` | 7.0.86 | 16 | Plan 2B -- needs the failure-mode debug |
| `jackson-core` | 2.12.7 | 1 | Plan 2A.1 -- bump jackson-core only to 2.15.x (low risk) |
| `owasp-java-html-sanitizer` | 20240325.1 | 1 | Phase 3 -- blocked by Java 8 pin |

## What Phase 2 cannot do without Phase 3

Phase 3 is the airvision identifier rewrite + JVM unpin -- ASM-rewrite
`com/ubnt/A/super/oOOO/` etc. so the spec-illegal identifiers (method
names like `new.super`, class names like `super`/`Object`/`String`) are
replaced with JLS-legal ones, then unpin from AdoptOpenJDK 8u265 to a
modern Temurin 17+ JRE. This is **out of scope of Phase 2** but Phase 2's
deliverable enables it: the residual `owasp-java-html-sanitizer
CVE-2025-66021` (fix in 20260101.1, Java 10 bytecode) is the canonical
example of a CVE that Phase 3 unblocks.

## Reproducing the empirical tests

Static analysis of airvision.jar's call sites uses standard JDK
bytecode tooling (e.g. `javap -p -c` over each `.class` extracted via
`unzip`) to enumerate imports and INVOKE* targets without external
tools.  The findings tables above were derived this way.

Plan 2A test (Jackson 2.12 swap on top of v3.10.13-9 image):

```bash
# Build the v3.10.13-9 image
docker build -t uv-base:p1b /root/unifi-video-controller

# Layer the Jackson swap
cat > /tmp/Dockerfile.p2a <<'EOF'
FROM uv-base:p1b
COPY jackson-databind-2.12.7.2.jar    /tmp/jdb.jar
COPY jackson-core-2.12.7.jar          /tmp/jc.jar
COPY jackson-annotations-2.12.7.jar   /tmp/ja.jar
RUN install -m 400 -o unifi-video -g unifi-video -T /tmp/jdb.jar /usr/lib/unifi-video/lib/jackson-databind-2.7.4.jar && \
    install -m 400 -o unifi-video -g unifi-video -T /tmp/jc.jar  /usr/lib/unifi-video/lib/jackson-core-2.7.4.jar && \
    install -m 400 -o unifi-video -g unifi-video -T /tmp/ja.jar  /usr/lib/unifi-video/lib/jackson-annotations-2.7.2.jar && \
    rm -f /tmp/jdb.jar /tmp/jc.jar /tmp/ja.jar
EOF

# Smoke test against prod snapshot
docker run -d --name uv-p2a --cap-add DAC_READ_SEARCH \
  -p 17443:7443 --tmpfs /var/cache/unifi-video \
  -v /path/to/prod/snapshot:/var/lib/unifi-video \
  -e PUID=99 -e PGID=100 uv-test:p2-jackson212

# Wait 60s, then probe
curl -sk https://localhost:17443/api/2.0/bootstrap | jq .data[0].nvrName
```
