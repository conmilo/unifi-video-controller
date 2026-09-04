# Phase 6 audit: Guava 14.0.1 reachability

**Status:** completed 2026-05-25 as part of `v3.10.13-23`.  Result:
**zero reachable call sites** for any of the three vulnerable Guava
14.0.1 APIs across every JAR the runtime image ships.  All three
CVEs (`CVE-2023-2976`, `CVE-2018-10237`, `CVE-2020-8908`) are
therefore suppressed in `.trivyignore` with the rationale "audit
shows no caller reaches the vulnerable API".

This document is the persistent evidence the suppression rests on.
If a future change adds a new JAR or modifies a shipped JAR, re-run
the script in [Re-running the audit](#re-running-the-audit); if the
result changes, the suppressions must be reconsidered.

## Why this audit instead of the full bump

`docs/PHASE-5-ROADMAP.md` "Phase 6 -- Guava 14.0.1 -> 32+" describes
the Guice-3.0/Guava-14 ABI coupling that makes a clean Guava bump
impossible without simultaneously bumping Guice to 5.1.0 and re-
auditing airvision's 38+ `com.google.inject.*` import surface.  That
work is rated **High** risk / 3+ sessions.

Suppression-via-reachability-audit is the standard project pattern
for "Trivy reports the CVE, but our environment doesn't expose the
vulnerable code path" cases -- exactly what Phase 4 (`v3.10.13-16`)
did for the 38 `libssl1.1` CVEs using Canonical's Ubuntu CVE JSON
API.  This audit is the JAR-bytecode analogue.

## Targets

The three CVEs and their vulnerable entry points:

| CVE | Severity | Vulnerable API | Bytecode target the audit checks |
|---|---|---|---|
| CVE-2023-2976 | medium | `com.google.common.io.Files.createTempDir()` | Methodref `com/google/common/io/Files.createTempDir` |
| CVE-2018-10237 | medium | `AtomicDoubleArray`, `CompoundOrdering` (via `Ordering.compound`) | Class `com/google/common/util/concurrent/AtomicDoubleArray`, Class `com/google/common/collect/CompoundOrdering`, Methodref `com/google/common/collect/Ordering.compound` |
| CVE-2020-8908 | low | `Files.createTempDir()` directory permissions | same target as CVE-2023-2976 |

A constant-pool `Methodref` / `InterfaceMethodref` entry is the JVM's
internal representation of "this code emits an invoke against the
named method".  `javac` adds an entry for **every** static
method invocation it compiles; entries are never added for methods
that are not referenced.  A `Class` entry covers type references
(`new ClassName`, field types, type arguments, checkcast, instanceof);
a `Utf8` string entry covers raw string literals that would be
required for any reflective dispatch via `Class.forName(...)`.

The audit script checks all three signal types (Methodref / Class /
Utf8 string) for every target.

## Scope

Three sets of JARs make up everything the runtime image exposes to
the JVM:

### Set A: upstream `unifi-video.deb` JAR bundle

82 JARs / 21,505 classes.  Extracted to `/root/uv-harden/work/lib/`.
This is the bundle the upstream `.deb` ships, before any image-build-
time modernization.  Scanning the .deb bundle (rather than only the
post-image-build set) is **deliberately conservative**: it audits
both the JARs that ship unchanged AND the JARs that get swapped out
at image build, so a new vulnerable call site in either category
would be visible.

Skipped: `guava-14.0.1.jar` itself (it DEFINES the vulnerable APIs;
the question is whether anything ELSE calls into it).

### Set B: Maven Central swap-in JARs

21 JARs / 13,900 classes.  Pulled fresh from Maven Central with the
URLs the Dockerfile's `fetcher` stage uses (`Dockerfile:93-113`).
These are the modernized replacements applied at image build time:

```text
bcpkix-jdk18on-1.84.jar          log4j-1.2-api-2.26.0.jar
bcprov-jdk18on-1.84.jar          log4j-api-2.26.0.jar
bcutil-jdk18on-1.84.jar          log4j-core-2.26.0.jar
commons-beanutils-1.11.0.jar     log4j-slf4j-impl-2.26.0.jar
commons-io-2.18.0.jar            owasp-java-html-sanitizer-20260101.1.jar
httpclient-4.5.14.jar            tomcat-dbcp-9.0.121.jar
jackson-annotations-2.12.7.jar   tomcat-embed-core-9.0.121.jar
jackson-core-2.21.4.jar          tomcat-embed-el-9.0.121.jar
jackson-databind-2.12.7.2.jar    tomcat-embed-jasper-9.0.121.jar
jbcrypt-0.4.jar                  tomcat-embed-websocket-9.0.121.jar
json-sanitizer-1.2.3.jar
```

### Set C: `uv-patcher.jar`

1 JAR / 1,093 classes.  Custom runtime patcher built from
`uv-patcher/pom.xml`.  Declared dependencies: ASM 9.7 +
jackson-databind 2.21.5 only (no Guava).  The shaded JAR ends up at
`/opt/uv-patcher/uv-patcher.jar` in the runtime image; the patcher
runs in its own JVM before `jsvc` launches UniFi Video.  Included
in the scan for completeness.

### Total

**104 JARs, 36,498 .class files**.  Every JAR loaded by either the
UniFi Video JVM (`jsvc` process) or the runtime patcher JVM is
covered.

## Result

```text
=== Set A (.deb bundle, /root/uv-harden/work/lib/) ===
JARs scanned:    82
Classes scanned: 21505
RESULT:          ZERO HITS

=== Set B (Maven Central swap-ins) ===
JARs scanned:    21
Classes scanned: 13900
RESULT:          ZERO HITS

=== Set C (uv-patcher.jar) ===
JARs scanned:    1
Classes scanned: 1093
RESULT:          ZERO HITS

=== Combined ===
JARs scanned:    104
Classes scanned: 36498
RESULT:          ZERO HITS
```

Per-CVE disposition:

| CVE | Suppress? | Justification |
|---|---|---|
| CVE-2023-2976 | YES | Zero `Files.createTempDir` invocations across all 104 JARs. |
| CVE-2018-10237 | YES | Zero `AtomicDoubleArray` / `CompoundOrdering` class references and zero `Ordering.compound` invocations across all 104 JARs. |
| CVE-2020-8908 | YES | Same target as CVE-2023-2976; same zero-hit result. |

## Caveats

Two ways the audit can be wrong:

1. **Reflective dispatch with a dynamically-constructed name.**  If
   any shipped code builds the class/method name from runtime data
   (e.g.,
   `Class.forName(configValue + ".Files").getMethod("createTempDir")`)
   and then invokes it via reflection, the audit's static
   constant-pool check would miss the call.  The audit also checks
   `Utf8` string literals for the three FQNs, which would catch a
   simple `Class.forName("com.google.common.io.Files")` -- still
   zero hits.  Truly dynamic dispatch (where neither the class nor
   the method name appears as a static string anywhere in the
   bytecode) is a theoretical hole, mitigated by the fact that none
   of the shipped JARs are in the business of doing dynamic Guava
   dispatch -- airvision uses Guice for DI, and Guice's binding
   mechanism uses class objects directly, not string names.

2. **Suppression rot.**  A future Dockerfile change could swap in a
   new JAR that DOES call the vulnerable APIs.  Mitigation: this
   doc is the persistent record of which JARs were audited; the
   audit script (`docs/audit-guava-phase6.py`) is self-contained
   and reproducible.  Any PR that adds or modifies a shipped JAR
   should re-run the audit and update this doc + `.trivyignore` if
   the result changes.

Neither caveat materially affects this round.  Suppression stands.

## Re-running the audit

Script: `docs/audit-guava-phase6.py`.  To re-run from a clean state:

```bash
# 1. Set A -- upstream .deb bundle.  Already extracted to
#    /root/uv-harden/work/lib/ from prior phases; rebuild with
#    `dpkg-deb -x unifi-video.Ubuntu18.04_amd64.v3.10.13.deb /root/uv-harden/work/`
#    if missing.

python3 docs/audit-guava-phase6.py /root/uv-harden/work/lib

# 2. Set B -- modernized Maven Central swap-ins.  URLs match the
#    Dockerfile fetcher stage's wget block (Dockerfile:93-113):
mkdir -p /tmp/uv-shipped-modern
cd /tmp/uv-shipped-modern
for url in \
  https://repo1.maven.org/maven2/org/apache/logging/log4j/log4j-api/2.26.0/log4j-api-2.26.0.jar \
  https://repo1.maven.org/maven2/org/apache/logging/log4j/log4j-core/2.26.0/log4j-core-2.26.0.jar \
  https://repo1.maven.org/maven2/org/apache/logging/log4j/log4j-1.2-api/2.26.0/log4j-1.2-api-2.26.0.jar \
  https://repo1.maven.org/maven2/org/apache/logging/log4j/log4j-slf4j-impl/2.26.0/log4j-slf4j-impl-2.26.0.jar \
  https://repo1.maven.org/maven2/commons-io/commons-io/2.18.0/commons-io-2.18.0.jar \
  https://repo1.maven.org/maven2/com/fasterxml/jackson/core/jackson-core/2.21.4/jackson-core-2.21.4.jar \
  https://repo1.maven.org/maven2/com/fasterxml/jackson/core/jackson-databind/2.12.7.2/jackson-databind-2.12.7.2.jar \
  https://repo1.maven.org/maven2/com/fasterxml/jackson/core/jackson-annotations/2.12.7/jackson-annotations-2.12.7.jar \
  https://repo1.maven.org/maven2/commons-beanutils/commons-beanutils/1.11.0/commons-beanutils-1.11.0.jar \
  https://repo1.maven.org/maven2/com/mikesamuel/json-sanitizer/1.2.3/json-sanitizer-1.2.3.jar \
  https://repo1.maven.org/maven2/org/bouncycastle/bcprov-jdk18on/1.84/bcprov-jdk18on-1.84.jar \
  https://repo1.maven.org/maven2/org/bouncycastle/bcpkix-jdk18on/1.84/bcpkix-jdk18on-1.84.jar \
  https://repo1.maven.org/maven2/org/bouncycastle/bcutil-jdk18on/1.84/bcutil-jdk18on-1.84.jar \
  https://repo1.maven.org/maven2/com/googlecode/owasp-java-html-sanitizer/owasp-java-html-sanitizer/20260101.1/owasp-java-html-sanitizer-20260101.1.jar \
  https://repo1.maven.org/maven2/org/apache/tomcat/embed/tomcat-embed-core/9.0.121/tomcat-embed-core-9.0.121.jar \
  https://repo1.maven.org/maven2/org/apache/tomcat/embed/tomcat-embed-el/9.0.121/tomcat-embed-el-9.0.121.jar \
  https://repo1.maven.org/maven2/org/apache/tomcat/embed/tomcat-embed-jasper/9.0.121/tomcat-embed-jasper-9.0.121.jar \
  https://repo1.maven.org/maven2/org/apache/tomcat/embed/tomcat-embed-websocket/9.0.121/tomcat-embed-websocket-9.0.121.jar \
  https://repo1.maven.org/maven2/org/apache/tomcat/tomcat-dbcp/9.0.121/tomcat-dbcp-9.0.121.jar \
  https://repo1.maven.org/maven2/org/apache/httpcomponents/httpclient/4.5.14/httpclient-4.5.14.jar \
  https://repo1.maven.org/maven2/org/mindrot/jbcrypt/0.4/jbcrypt-0.4.jar; do
  wget -q "$url"
done

python3 docs/audit-guava-phase6.py /tmp/uv-shipped-modern

# 3. Set C -- uv-patcher.jar.  Build then scan:
(cd uv-patcher && mvn -B clean package)
mkdir -p /tmp/uv-patcher-scan
cp uv-patcher/target/uv-patcher.jar /tmp/uv-patcher-scan/

python3 docs/audit-guava-phase6.py /tmp/uv-patcher-scan
```

Expected output on each run: `RESULT: ZERO HITS`.  If any run reports
hits, document them in this file and re-evaluate the suppression
list in `.trivyignore`.
