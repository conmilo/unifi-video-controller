# Phase 5 roadmap: BouncyCastle + Guava (medium-CVE residuals)

> **Status (2026-05-25):** Phase 5 SHIPPED in `v3.10.13-19` -- see
> CHANGELOG entry for what landed.  Bumped to **1.84** (not the 1.78.1
> originally scoped here; 1.84 was the current latest at PR time).
> `bcprov-ext` and `bctls` were retired entirely rather than bumped
> (rationale in CHANGELOG).  Phase 6 (Guava + Guice) remains open.
>
> **Original status (2026-05-24):** assessment only.  Phase 4
> (`v3.10.13-16`) closed the low-risk medium/low CVE sweep and
> deferred these two JAR families because their bump cost is materially
> higher than the rest.  This document scopes the work for whoever picks
> up Phase 5 / Phase 6.

After Phase 4 ships, the Trivy Security tab will still show 13 medium
alerts against two JAR families that Phase 4 explicitly deferred:

| Package | File | CVE count | Phase |
|---|---|---:|---|
| `org.bouncycastle:bcpkix-jdk15on` | `lib/bcpkix-jdk15on-160.jar` | 2 medium | 5 |
| `org.bouncycastle:bcprov-jdk15on` | `lib/bcprov-jdk15on-160.jar` | 5 medium | 5 |
| `org.bouncycastle:bcprov-ext-jdk15on` | `lib/bcprov-ext-jdk15on-160.jar` | 3 medium | 5 |
| `com.google.guava:guava` | `lib/guava-14.0.1.jar` | 2 medium + 1 low | 6 |

Total: 12 medium + 1 low = 13 alerts.  Both families are real exposure
(no false positives, no ESM caveat) -- they're deferred only because
the bump is materially riskier than the Phase 4 set.

---

## Phase 5 -- BouncyCastle 1.60 -> 1.78+ (10 alerts)

### CVE inventory

| CVE | Affected JAR | Severity | One-line summary |
|---|---|---|---|
| CVE-2026-5588 | `bcpkix-jdk15on-160.jar` | medium | PKIX `CompositeVerifier` accepts empty signature sequence as valid |
| CVE-2025-8916 | `bcpkix-jdk15on-160.jar` | medium | BouncyCastle DoS in some PKIX path |
| CVE-2024-30171 | `bcprov-jdk15on-160.jar` | medium | Timing variant of Bleichenbacher (Marvin attack) |
| CVE-2024-29857 | `bcprov-jdk15on-160.jar` | medium | EC F2m parameters DoS in certificate import |
| CVE-2023-33202 | `bcprov-jdk15on-160.jar` + `bcprov-ext-jdk15on-160.jar` | medium | OOM via crafted ASN.1 in `PEMParser` |
| CVE-2020-26939 | `bcprov-jdk15on-160.jar` + `bcprov-ext-jdk15on-160.jar` | medium | Side-channel on RSA decryption with `OAEPPadding` |
| CVE-2020-15522 | `bcprov-jdk15on-160.jar` + `bcprov-ext-jdk15on-160.jar` | medium | Timing issue within EC math library |

7 distinct CVEs spread across the 3 JAR artifacts (`bcprov-jdk15on`,
`bcprov-ext-jdk15on`, `bcpkix-jdk15on`) -- Trivy reports the same CVE
twice when it affects two JARs, hence 10 alerts for 7 underlying CVEs.

### Surface to audit

airvision's `org.bouncycastle.*` import surface spans:

- **`asn1.x500.*`** -- `X500Name`, `X500NameBuilder` (subject DN
  construction for self-signed certs).
- **`asn1.x509.*`** -- `BasicConstraints`, `Extension`, `KeyUsage`,
  `SubjectPublicKeyInfo` (X.509 v3 extension assembly).
- **`cert.*` and `cert.jcajce.*`** -- `X509v3CertificateBuilder`,
  `JcaX509CertificateConverter`, `JcaX509ExtensionUtils`,
  `JcaX509v1CertificateBuilder`, `JcaX509v3CertificateBuilder`.
- **`jce.provider.*`** -- `BouncyCastleProvider` (registered globally
  via `Security.addProvider`).
- **`openssl.*` and `openssl.jcajce.*`** -- `PEMEncryptor`, `PEMKeyPair`,
  `PEMParser`, `PEMWriter`, `JcaPEMKeyConverter`, `JcaPEMWriter`,
  `JcePEMEncryptorBuilder`.
- **`operator.*` and `operator.jcajce.*`** -- `ContentSigner`,
  `JcaContentSignerBuilder`.
- **`pkcs.*` and `pkcs.jcajce.*`** -- `PKCS10CertificationRequest`,
  `PKCS10CertificationRequestBuilder`,
  `JcaPKCS10CertificationRequestBuilder`.

Roughly 24 distinct imports, all from public
`org.bouncycastle.{asn1,cert,jce,openssl,operator,pkcs}.*` packages.
Every type listed exists in 1.78+ with the same signature -- spot-
checked against Maven Central's published javadoc for 1.78.

Functional surface in airvision:

1. **Self-signed certificate generation** (`X509v3CertificateBuilder`
   + `JcaContentSignerBuilder`) -- used for the controller's self-
   signed `airvision` certificate that the `:7443` / `:7442` Tomcat
   connectors present.  Regenerated on demand.
2. **PEM read/write** (`PEMParser`, `PEMWriter`,
   `JcaPEMKeyConverter`) -- used for `keystore` / `cam-keystore`
   import/export and the adoption flow when a camera supplies its
   own cert.
3. **PKCS#10 CSR** (`PKCS10CertificationRequest{,Builder}`) -- camera
   adoption "RSA key + signing request" exchange.
4. **JCE provider registration** (`Security.addProvider(new
   BouncyCastleProvider())` -- in airvision's `service/security/*`
   package init).

None of these touch CompositeVerifier (CVE-2026-5588) or directly
trigger the EC timing paths -- but the JCE provider being registered
globally means any TLS handshake / signature verification across the
JVM could land in BC.

### Filename rename consideration

All `jdk15on` artifacts were retired and replaced with `jdk18on`
starting in BouncyCastle 1.71 (2022).  The current latest is 1.78.1.
`jdk18on` artifacts require Java 11+, which is satisfied since
`v3.10.13-13` (OpenJDK 21 LTS).

airvision's Manifest Class-Path references `bcprov-jdk15on-160.jar`,
`bcprov-ext-jdk15on-160.jar`, and `bcpkix-jdk15on-160.jar` (verified
via `unzip -p airvision.jar META-INF/MANIFEST.MF`).  The patcher
rename map needs three new entries (commit-time syntax):

- `bcprov-jdk15on-160.jar` -> `bcprov-jdk18on-1.78.1.jar`
- `bcprov-ext-jdk15on-160.jar` -> `bcprov-ext-jdk18on-1.78.1.jar`
- `bcpkix-jdk15on-160.jar` -> `bcpkix-jdk18on-1.78.1.jar`

Plus the matching Dockerfile fetcher + install + rm work.

### Tomcat 9 BCJSSE interaction

After the Phase 3.4 cipher fix (`@CHANGELOG.md` v3.10.13-14), the
`:7442` connector relies on JDK-default JSSE cipher selection.  We
don't currently install `bctls-jdk15on` as a JSSE provider -- the JDK
21 JSSE handles TLS.  So BC is invoked only for the cert/CSR
operations listed above, not for TLS connector negotiation itself.

If the Phase 5 audit decides to ALSO swap BCJSSE into the connector
path (to harden TLS further), that's a deliberate scope expansion --
not free with the 1.78 bump.

### Smoke battery (recommended for the Phase 5 PR)

1. Container reaches `(healthy)` <= 90s against `/root/uv-smoke-data/`.
2. `openssl s_client -connect 127.0.0.1:7443 -tls1_2` + `-connect
   127.0.0.1:7442 -tls1_2` from inside the container.  Both negotiate
   cleanly (the Phase 3.4 connector fix is regression-tested).
3. Camera adoption (one G3 + one G4 if available) -- exercises both
   the self-signed cert path and the PKCS#10 CSR exchange.
4. 24h soak run -- catches any subtle PKIX validation regression in
   the OCSP/CRL paths.

### Risk verdict

**Medium-high.**  The BC API surface airvision uses is small and
public, but the JCE-provider-registered-globally pattern means
unintended call sites can land in BC.  Plan ~2-3 focused sessions:
one for the swap + smoke, one for any regression debug, one for a
soak observation period.

---

## Phase 6 -- Guava 14.0.1 -> 32+ (3 alerts) + Guice 3.0 -> 5.1.0

### CVE inventory

| CVE | Severity | Fixed in | Summary |
|---|---|---|---|
| CVE-2023-2976 | medium | 32.0.0-android / -jre | Insecure temp directory creation in `Files.createTempDir()` |
| CVE-2018-10237 | medium | 24.1.1-android / -jre | Unbounded memory allocation in `AtomicDoubleArray`, `CompoundOrdering` |
| CVE-2020-8908 | low | 30.0-android / -jre | Temp directory permission |

All three close at Guava 32+; 32.0.0-jre is the floor for closing all
of them in one bump.

### Constraint: Guice 3.0 ABI coupling

Guice 3.0 (the version airvision bundles) was compiled against Guava
14.0.1 and references internal Guava types that changed in 20+:

- `MapMaker.makeMap()` -- removed in Guava 21.
- `CacheBuilder.softValues()` -- behavior change in Guava 21+.
- `Function`, `Supplier`, `Predicate` (Guava versions) -- moved to
  `java.util.function` equivalents in Guava 30+.

So bumping Guava without bumping Guice will throw `NoSuchMethodError`
or `NoClassDefFoundError` at Guice module initialisation -- which
happens at Tomcat context startup, so the entire web service 404s.

Per the inventory at `@docs/JAR-INVENTORY.md` (the Guava section), the
airvision Guice surface spans 38+ distinct `com.google.inject.*`
imports across the codebase.

### Three paths

#### 1. Bump Guice to 5.1.0 + Guava to 32.0.0-jre (recommended)

Guice 5.1.0 (current latest) was released against Guava 31.0.1-jre and
tested against Guava 32.x.

Risk: airvision binds modules via `@Inject`, custom `Module` classes
that extend `AbstractModule`, and `@Provides` methods.  Guice 4.x
removed some 3.0 surface:

- `Stage.PRODUCTION` constructor signature changes (3.0 accepted a
  `Module...` varargs, 4.x prefers `Guice.createInjector(Stage.PROD,
  Module...)`).  airvision spots: TBD (call-site audit).
- `@Provides @Override` -- removed in 4.x; airvision spots: TBD.
- `TypeLiteral(Type type)` constructor -- changed in 4.x; airvision
  spots: TBD.
- `ServletModule` Class-Path: changed Guice servlet's package layout
  in 4.x.  airvision uses `guice-servlet-3.0.jar`.

Full call-site audit required:

- Enumerate every `com.google.inject.*` reference in airvision's
  bytecode.
- For each call site, check the Guice 5.1.0 javadoc / source.

Three Guice JARs to bump in lockstep:

- `guice-3.0.jar` -> `guice-5.1.0.jar`
- `guice-servlet-3.0.jar` -> `guice-servlet-5.1.0.jar`
- Plus the 8 satellite packages from inventory (`guice-grapher-3.0`,
  `guice-jmx-3.0`, etc.) -- each may need to be the 5.1.0 version OR
  may have been merged into core in 4.x (in which case the jar gets
  removed entirely and airvision's Class-Path token loses the rename
  target).

Plus the matching airvision-renames.json + Dockerfile + SHA256SUMS work.

#### 2. Keep Guice 3.0, bump Guava to highest compatible version

Guice 3.0 will accept Guava up to ~19.0 before hitting the
`MapMaker.makeMap()` ABI break.  But Guava 19.0 doesn't close
CVE-2023-2976 (fixed in 32+) or CVE-2018-10237 (fixed in 24+).

**Dead end** -- the highest Guava that Guice 3.0 tolerates is below
the CVE fix floor.

#### 3. Shaded Guice 5.1.0 + Guava 32+

Ship a relocated Guice/Guava (e.g. under `com.conmilo.shaded.guice`
/ `com.conmilo.shaded.guava`) alongside the un-shaded 3.0 / 14.0.1.
Have airvision use the shaded versions via a Class-Path rewrite.

Risk: classpath collision (both Guava versions visible to the JVM),
service-loader resolution problems, complex to ship and verify.  Not
worth it.

### Smoke battery (recommended for the Phase 6 PR)

1. Container reaches `(healthy)` <= 90s.
2. `GET /` -> 200 (Guice ServletModule wiring is functional).
3. `GET /api/2.0/server` -> 200 with NVR info (Guice-injected service
   classes are functional).
4. `POST /api/2.0/login` (real creds) -> 200 with session cookie
   (Guice-injected auth flow works).
5. Camera live view in browser -> live frames arrive (Guice-injected
   WebRTC / streaming flow works).

### Risk verdict

**High.**  Path 1 (Guice 4/5 + Guava 32) is the only real option, but
it's an honest 3+ session of focused work including a full audit pass
over airvision's Guice module wiring.  Defer to Phase 6 (or later);
Phase 5 should ship and stabilise first.

---

## Why these aren't in Phase 4

Phase 4 closed the **low-risk** medium/low subset (jackson-core,
httpclient, jbcrypt, log4j) where bumps were drop-in and the smoke
battery was the standard 4-endpoint probe.  BC and Guava both fail
the "drop-in" bar:

- BC has a large JCE-provider surface that interacts with the JDK's
  default JSSE and with arbitrary signature-verification code paths.
- Guava is wired into Guice 3.0's internals; you cannot bump one
  without the other, and Guice 5.1 has its own API delta to audit.

Sequencing recommendation: Phase 5 (BC) first, because the smoke
surface is smaller (TLS connectors + cert paths), then Phase 6 (Guava
+ Guice) once Phase 5 stabilises.  Don't bundle them -- each one
deserves its own PR + reviewer attention.
