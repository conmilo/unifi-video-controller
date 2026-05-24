# syntax=docker/dockerfile:1.7
# ===========================================================================
# UniFi Video Controller 3.10.13 -- modernized container image
# ---------------------------------------------------------------------------
# Base:    Ubuntu 24.04 LTS (pinned by digest, security-supported to 2029)
# JRE:     Canonical OpenJDK 21 LTS HotSpot (apt-installed since Phase 3.1)
#          Phase 3 (CHANGELOG v3.10.13-13) introduced the uv-patcher runtime
#          tool that rewrites airvision.jar's spec-illegal identifiers at
#          container start.  This unblocked the move off the AdoptOpenJDK
#          8u265-b01 pin that v3.10.13-4 required.  See README.md "JRE
#          history" for the empirical backstory.
# MongoDB: 4.4.29 (last AVX-free MongoDB; runtime DB)
#          + 4.2.25 (intermediate, used once for 4.0 -> 4.2 fCV step)
# Target:  linux/amd64 only (Synology DS918+ / Celeron J3455 / Goldmont)
# ===========================================================================
# Patches that apply at runtime (uv-patcher; see uv-patcher/README.md):
#   - airvision.jar: rename the 6 obfuscated classes under
#     com/ubnt/A/super/oOOO/ (JLS-reserved class names like 'super'/'Object'/
#     'String', method names containing literal '.', method/field names
#     using Java reserved words).
#   - tomcat-embed-core-9.0.118.jar: inject 2 instance-method shims
#     (setCatalinaBase/setCatalinaHome) that Tomcat 9 removed but airvision
#     still calls during the Guice bootstrap.
# Both rewrites happen in the running container's writable layer; the image
# layer always carries pristine Ubiquiti / Apache bytes.
# ===========================================================================

# Multi-arch index digest for ubuntu:24.04 (resolved 2026-04-15)
ARG UBUNTU_DIGEST=sha256:c4a8d5503dfb2a3eb8ab5f807da5bc69a85730fb49b5cfca2330194ebcc41c7b

# ---------------------------------------------------------------------------
# Stage 1: fetcher -- download + verify all pinned third-party artifacts
# ---------------------------------------------------------------------------
FROM ubuntu:24.04@${UBUNTU_DIGEST} AS fetcher

# hadolint ignore=DL3008,DL3015
RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get -y upgrade && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        ca-certificates \
        wget && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /artifacts
COPY checksums/SHA256SUMS ./SHA256SUMS

# All artifacts downloaded in a single layer so failure to verify any one
# aborts the build before later stages copy from this image.
#
# Phase 3.1 moved log4j (api/core/1.2-api), commons-collections-3.2.2,
# jettison-1.5.4, and the JRE itself to Ubuntu noble apt at runtime-stage
# install time.  Commons-collections, jettison, and the JRE remain on apt.
# Phase 4 (v3.10.13-16) moved log4j BACK to Maven Central because Ubuntu
# noble's apache-log4j2 source package is still at 2.19.0-2build1, which
# carries CVE-2025-68161 and CVE-2026-34477/34479/34480; the fixes need
# log4j 2.25+, and Canonical has not refreshed the package in either
# noble OR the new resolute (26.04 LTS).  We track all four log4j JARs
# from Maven Central directly until apt catches up.
RUN set -eux; \
    wget -q https://fastdl.mongodb.org/linux/mongodb-linux-x86_64-ubuntu2004-4.4.29.tgz; \
    wget -q https://fastdl.mongodb.org/linux/mongodb-linux-x86_64-ubuntu1804-4.2.25.tgz; \
    wget -q https://dl.ubnt.com/firmwares/ufv/v3.10.13/unifi-video.Ubuntu18.04_amd64.v3.10.13.deb; \
    wget -q http://security.ubuntu.com/ubuntu/pool/main/o/openssl/libssl1.1_1.1.1f-1ubuntu2.24_amd64.deb; \
    wget -q https://repo1.maven.org/maven2/org/apache/logging/log4j/log4j-api/2.26.0/log4j-api-2.26.0.jar; \
    wget -q https://repo1.maven.org/maven2/org/apache/logging/log4j/log4j-core/2.26.0/log4j-core-2.26.0.jar; \
    wget -q https://repo1.maven.org/maven2/org/apache/logging/log4j/log4j-1.2-api/2.26.0/log4j-1.2-api-2.26.0.jar; \
    wget -q https://repo1.maven.org/maven2/org/apache/logging/log4j/log4j-slf4j-impl/2.26.0/log4j-slf4j-impl-2.26.0.jar; \
    wget -q https://repo1.maven.org/maven2/commons-io/commons-io/2.18.0/commons-io-2.18.0.jar; \
    wget -q https://repo1.maven.org/maven2/com/fasterxml/jackson/core/jackson-core/2.21.3/jackson-core-2.21.3.jar; \
    wget -q https://repo1.maven.org/maven2/com/fasterxml/jackson/core/jackson-databind/2.12.7.2/jackson-databind-2.12.7.2.jar; \
    wget -q https://repo1.maven.org/maven2/com/fasterxml/jackson/core/jackson-annotations/2.12.7/jackson-annotations-2.12.7.jar; \
    wget -q https://repo1.maven.org/maven2/commons-beanutils/commons-beanutils/1.11.0/commons-beanutils-1.11.0.jar; \
    wget -q https://repo1.maven.org/maven2/com/mikesamuel/json-sanitizer/1.2.3/json-sanitizer-1.2.3.jar; \
    wget -q https://repo1.maven.org/maven2/com/googlecode/owasp-java-html-sanitizer/owasp-java-html-sanitizer/20260101.1/owasp-java-html-sanitizer-20260101.1.jar; \
    wget -q https://repo1.maven.org/maven2/org/apache/tomcat/embed/tomcat-embed-core/9.0.118/tomcat-embed-core-9.0.118.jar; \
    wget -q https://repo1.maven.org/maven2/org/apache/tomcat/embed/tomcat-embed-el/9.0.118/tomcat-embed-el-9.0.118.jar; \
    wget -q https://repo1.maven.org/maven2/org/apache/tomcat/embed/tomcat-embed-jasper/9.0.118/tomcat-embed-jasper-9.0.118.jar; \
    wget -q https://repo1.maven.org/maven2/org/apache/tomcat/embed/tomcat-embed-websocket/9.0.118/tomcat-embed-websocket-9.0.118.jar; \
    wget -q https://repo1.maven.org/maven2/org/apache/tomcat/tomcat-dbcp/9.0.118/tomcat-dbcp-9.0.118.jar; \
    wget -q https://repo1.maven.org/maven2/org/apache/httpcomponents/httpclient/4.5.14/httpclient-4.5.14.jar; \
    wget -q https://repo1.maven.org/maven2/org/mindrot/jbcrypt/0.4/jbcrypt-0.4.jar; \
    sha256sum -c SHA256SUMS

# ---------------------------------------------------------------------------
# Stage 2: patcher-builder -- compile uv-patcher.jar (Maven, single fat jar)
# Replaces the v3.10.13-11 'tomcat-patcher' stage.  Same shape: builds a
# small Java artefact that the runtime stage consumes via COPY --from=.
# See uv-patcher/README.md for the design.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jdk AS patcher-builder

# hadolint ignore=DL3008
RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get -y upgrade && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        maven && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /build
COPY uv-patcher/pom.xml                  ./pom.xml
COPY uv-patcher/src                      ./src

# `mvn -B clean package` builds + runs unit tests + assembles the shaded jar.
# Test failure aborts the docker build (which is what we want).
RUN mvn -B clean package && \
    test -f target/uv-patcher.jar && \
    echo "uv-patcher.jar size:" && du -h target/uv-patcher.jar

# ---------------------------------------------------------------------------
# Stage 3: runtime image
# ---------------------------------------------------------------------------
FROM ubuntu:24.04@${UBUNTU_DIGEST}

LABEL org.opencontainers.image.title="UniFi Video Controller (modernized)" \
      org.opencontainers.image.description="UniFi Video 3.10.13 on Ubuntu 24.04 + OpenJDK 21 LTS + MongoDB 4.4 (with 4.0->4.2->4.4 fCV migration); airvision identifier rewrite + Tomcat 9 Bootstrap shim applied at runtime by uv-patcher" \
      org.opencontainers.image.source="https://github.com/conmilo/unifi-video-controller" \
      org.opencontainers.image.url="https://github.com/conmilo/unifi-video-controller" \
      org.opencontainers.image.documentation="https://github.com/conmilo/unifi-video-controller/blob/main/README.md" \
      org.opencontainers.image.licenses="MIT" \
      org.opencontainers.image.authors="conmilo (modernization fork), pducharme (original)" \
      org.opencontainers.image.vendor="conmilo" \
      org.opencontainers.image.base.name="docker.io/library/ubuntu:24.04"

ENV DEBIAN_FRONTEND=noninteractive \
    LC_ALL=C.UTF-8 \
    LANG=en_US.UTF-8 \
    LANGUAGE=en_US.UTF-8 \
    JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
    PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:/opt/mongodb-4.4/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    PUID=99 \
    PGID=100 \
    UMASK=002 \
    TZ=Etc/UTC \
    CREATE_TMPFS=no \
    DEBUG=0 \
    USE_HOST_TMPFS=no \
    USE_UNIFI_TMPFS=no

# -------- System packages ---------------------------------------------------
# hadolint ignore=DL3008
#
# Phase 3.1 -- moved from manual tarball/Maven fetch to apt for these:
#   - openjdk-21-jre-headless    (Canonical OpenJDK 21 LTS; replaces Temurin tarball)
#   - libcommons-collections3-java (3.2.2; replaces Maven Central wget)
#   - libjettison-java           (1.5.4; replaces Maven Central wget)
#
# Phase 4 (v3.10.13-16) -- reverted log4j from apt back to Maven Central.
# liblog4j2-java in BOTH noble and resolute (26.04 LTS) is pinned at
# 2.19.0-2build1, which carries CVE-2025-68161 and CVE-2026-34477/34479/
# 34480.  The fixes need log4j 2.25+, and Canonical has not refreshed
# the package.  All four log4j JARs (api, core, 1.2-api, slf4j-impl)
# now come from the fetcher stage at 2.26.0; liblog4j2-java is no
# longer installed.
#
# Phase 3.2 -- added JAXB + JAF runtime libraries removed from the JDK in
# Java 11.  airvision (Java 8 build) still imports javax.xml.bind and
# javax.activation directly; without these JARs on the Class-Path, the
# Guice filter's annotation processing throws TypeNotPresentException at
# Tomcat context startup and the entire web service 404s.
#   - libjaxb-api-java           (jaxb-api 2.3.1)
#   - libjaxb-java               (jaxb-runtime 2.3.0.1 + transitive deps:
#                                 jaxb-core, istack-commons-runtime,
#                                 stax-ex, txw2, javax.activation 1.2.0)
#
# Trade-offs documented in the Phase 3.1 CHANGELOG entry.  We still install
# the openjdk-8-jre-headless equivs stub BEFORE this apt-install so the
# unifi-video .deb's hard-coded Depends: line resolves; the stub is a
# zero-file metapackage, the real JRE is Canonical's openjdk-21-jre-headless.
#
# Phase 4 follow-up (v3.10.13-17): `apt-get -y upgrade` pulls pending
# noble-security patches for transitive dependencies already present in
# the base image (libgnutls30t64, sed, libc6, etc.).  Without it, those
# packages stay frozen at the version baked into the `ubuntu:24.04`
# digest pin until Docker Hub republishes the tag -- which can lag the
# noble-security pool by weeks.  The Maven Central artifacts and the
# libssl1.1 deb remain SHA256-pinned (no freshness in those paths); only
# the Canonical-signed apt pool gains freshness.
RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get -y upgrade && \
    apt-get install -y --no-install-recommends \
        adduser \
        ca-certificates \
        curl \
        equivs \
        jq \
        jsvc \
        libcap2-bin \
        libcommons-collections3-java \
        libcurl4 \
        libfontconfig1 \
        libjaxb-api-java \
        libjaxb-java \
        libjettison-java \
        libxi6 \
        libxrender1 \
        libxtst6 \
        locales \
        lsb-release \
        moreutils \
        openjdk-21-jre-headless \
        patch \
        psmisc \
        sudo \
        tini \
        tzdata \
        unzip \
        wget && \
    locale-gen en_US.UTF-8 && \
    rm -rf /var/lib/apt/lists/*

# -------- libssl1.1 (bundled MongoDB 4.4 mongod needs legacy openssl) ------
# The mongodb-linux-x86_64-ubuntu2004-4.4.29.tgz binary at /opt/mongodb-4.4/
# was built on Ubuntu 20.04 and dynamically links libssl.so.1.1 +
# libcrypto.so.1.1.  Ubuntu 24.04 only ships libssl3, so we install
# libssl1.1 from the focal-security pool.  This pin is permanent until we
# can move off MongoDB 4.4 -- and we can't, because 5.0+ requires AVX and
# the deploy target (Apollo Lake Celeron J3455) doesn't have AVX.
# Verified via `objdump -p .../mongod | grep NEEDED`.
# UV-bundled JNI .so files (libubnt_*_jni.so, libsigar-amd64-linux.so) and
# the unifi-video.deb itself do NOT link against libssl/libcrypto.
COPY --from=fetcher /artifacts/libssl1.1_1.1.1f-1ubuntu2.24_amd64.deb /tmp/libssl1.1.deb
RUN dpkg -i /tmp/libssl1.1.deb && rm /tmp/libssl1.1.deb

# -------- Equivs stubs for openjdk-8-jre-headless + mongodb-server ---------
# UniFi Video's .deb hard-depends on `openjdk-8-jre-headless` (gone from
# Ubuntu 24.04) and `mongodb-server | mongodb-org-server | mongodb-10gen`
# (we ship MongoDB as tarballs in /opt, which dpkg can't see). We satisfy
# both via equivs stubs (zero-file metapackages with Provides:) so the
# unifi-video postinst is happy. The real JRE (Canonical OpenJDK 21) is
# installed via apt in the system-packages block above; MongoDB runtimes
# are installed by a subsequent tarball-extract stage.
COPY openjdk-8-equivs.control    /tmp/openjdk-8-equivs.control
COPY mongodb-server-equivs.control /tmp/mongodb-server-equivs.control
RUN cd /tmp && \
    equivs-build openjdk-8-equivs.control && \
    equivs-build mongodb-server-equivs.control && \
    dpkg -i openjdk-8-jre-headless_*.deb mongodb-server_*.deb && \
    rm -f openjdk-8-equivs.control mongodb-server-equivs.control \
          openjdk-8-jre-headless_*.deb openjdk-8-jre-headless_*.buildinfo openjdk-8-jre-headless_*.changes \
          mongodb-server_*.deb         mongodb-server_*.buildinfo         mongodb-server_*.changes

# -------- OpenJDK 21 JRE verification --------------------------------------
# The JRE itself is installed via apt-get (openjdk-21-jre-headless) in the
# system-packages block above.  This step just verifies the runtime is
# present at the expected path and reports its version into the build log
# for audit.
#
# Phase 3.1 (CHANGELOG v3.10.13-13) retired the manual Temurin tarball
# fetch in favour of Canonical's openjdk-21-jre-headless.  Trade-off: we
# get apt-managed CVE patching on the JRE, lose Adoptium's branded LTS
# guarantee (Canonical commits to LTS support of openjdk-21 within
# Ubuntu 24.04's standard 5-year window).  Ubiquiti's
# /usr/sbin/unifi-video init script discovers JAVA_HOME via
# `readlink -f $(which java)`; apt's openjdk-21 ships
# /usr/bin/java -> /etc/alternatives/java -> /usr/lib/jvm/java-21-openjdk-amd64/bin/java
# automatically, so no manual update-alternatives needed.
RUN java -version && \
    test -x /usr/bin/java && \
    test -d "${JAVA_HOME}"

# -------- MongoDB 4.4 runtime + 4.2 fCV stepper ----------------------------
COPY --from=fetcher /artifacts/mongodb-linux-x86_64-ubuntu2004-4.4.29.tgz /tmp/mongo44.tgz
COPY --from=fetcher /artifacts/mongodb-linux-x86_64-ubuntu1804-4.2.25.tgz /tmp/mongo42.tgz
RUN mkdir -p /opt && \
    tar -xzf /tmp/mongo44.tgz -C /opt && \
    mv /opt/mongodb-linux-x86_64-ubuntu2004-4.4.29 /opt/mongodb-4.4 && \
    tar -xzf /tmp/mongo42.tgz -C /opt && \
    mv /opt/mongodb-linux-x86_64-ubuntu1804-4.2.25 /opt/mongodb-4.2 && \
    rm /tmp/mongo44.tgz /tmp/mongo42.tgz && \
    ln -s /opt/mongodb-4.4/bin/mongo /usr/local/bin/mongo && \
    ln -s /opt/mongodb-4.4/bin/mongod /usr/local/bin/mongod && \
    ln -s /opt/mongodb-4.2/bin/mongod /usr/local/bin/mongod-4.2 && \
    mongod --version && \
    mongod-4.2 --version

# -------- Pre-create unifi-video user/group (deterministic UID/GID) --------
# The .deb's postinst would create these with whatever the next free UID was;
# pinning them up front lets us use names (not numeric IDs) for chown later.
RUN groupadd -r unifi-video && \
    useradd -r -g unifi-video -d /var/lib/unifi-video -s /usr/sbin/nologin unifi-video

# -------- UniFi Video .deb + patch -----------------------------------------
COPY --from=fetcher /artifacts/unifi-video.Ubuntu18.04_amd64.v3.10.13.deb /tmp/unifi-video.deb
COPY unifi-video.patch /tmp/unifi-video.patch
# systemctl symlink: the .deb postinst calls `systemctl daemon-reload` which
# would fail in a container; we replace it with /bin/true for the duration
# of the install only.
RUN ln -sf /bin/true /usr/local/bin/systemctl && \
    dpkg -i /tmp/unifi-video.deb && \
    rm /usr/local/bin/systemctl && \
    patch -lN /usr/sbin/unifi-video /tmp/unifi-video.patch && \
    rm /tmp/unifi-video.deb /tmp/unifi-video.patch && \
    \
    # Phase 3.4 (v3.10.13-14): rewrite the camera-management connector's \
    # cipher list in /usr/lib/unifi-video/conf/server.xml. \
    # \
    # The .deb ships /usr/lib/unifi-video/conf/server.xml with two ciphers \
    # on the :7442 Connector: \
    #   ciphers="TLS_RSA_WITH_AES_128_GCM_SHA256, \
    #            TLS_RSA_WITH_AES_128_GCM_SHA384" \
    # \
    # The second name is a typo (SHA384 only pairs with AES-256 in any TLS \
    # spec -- there is no TLS_RSA_WITH_AES_128_GCM_SHA384) and gets \
    # silently dropped by every JSSE provider.  OpenJDK 8u265's JSSE was \
    # permissive enough to accept the first one, so on v3.10.13-12 and \
    # earlier the connector had exactly one usable cipher and cameras \
    # negotiated.  OpenJDK 21's JSSE does not recognize \
    # TLS_RSA_WITH_AES_128_GCM_SHA256 under that exact spelling either \
    # (skipped with a "Some of the specified [ciphers] are not supported \
    # by the configured SSL engine" WARN in server.log), so the connector \
    # ends up with ZERO usable ciphers and every camera-to-controller \
    # WSS handshake closes silently after Client Hello -- the camera \
    # stays in "Managing" forever. \
    # \
    # Replacement list keeps the two intended ciphers (re-spelled in JSSE \
    # canonical form), adds ECDHE-RSA variants that JDK 21 prefers by \
    # default and that all UV camera firmwares from G3 onward support, \
    # and keeps legacy CBC ciphers for very old G3 firmware that \
    # predates ECDHE support.  All ciphers in the new list are in \
    # OpenJDK 21's enabled-by-default set (verified against \
    # `jdk.tls.disabledAlgorithms` defaults). \
    # \
    # The user-facing :7443 connector's cipher list is left alone -- it \
    # has 14 ciphers configured and 7 survive JDK 21's filtering, which \
    # is more than enough for any modern browser. \
    sed -i 's|ciphers="TLS_RSA_WITH_AES_128_GCM_SHA256,TLS_RSA_WITH_AES_128_GCM_SHA384"|ciphers="TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256,TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384,TLS_RSA_WITH_AES_128_GCM_SHA256,TLS_RSA_WITH_AES_256_GCM_SHA384,TLS_RSA_WITH_AES_128_CBC_SHA256,TLS_RSA_WITH_AES_256_CBC_SHA256,TLS_RSA_WITH_AES_128_CBC_SHA,TLS_RSA_WITH_AES_256_CBC_SHA"|' \
        /usr/lib/unifi-video/conf/server.xml && \
    # Fail loudly if the in-place edit didn't take (the .deb might ship \
    # a tweaked server.xml in a future ufv release; this guard means a \
    # rebuild will halt with a visible error instead of silently \
    # producing an image that still has the broken cipher list). \
    grep -q 'TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384' /usr/lib/unifi-video/conf/server.xml && \
    \
    chown -R unifi-video:unifi-video /usr/lib/unifi-video

# The recursive chown above is essential: UniFi Video's JVM runs as the
# unifi-video user (via jsvc) and at startup writes a regenerated
# EvoStream config to /usr/lib/unifi-video/conf/evostream/config.lua.
# The unifi-video.deb postinst's own ownership pass is not reliable in
# a non-interactive dpkg context (some files end up root-owned regardless
# of when the unifi-video user is created), so we re-assert it here.
# See CHANGELOG v3.10.13-6 for the original investigation that surfaced this.

# -------- Phase 3 bundled JAR refresh --------------------------------------
# Drop in the modernized JAR set with proper version filenames, then remove
# the original .deb-installed filenames so the lib/*.jar glob only picks up
# one copy of each library.  airvision.jar's Manifest Class-Path was rewritten
# by uv-patcher (at container start) to reference these new filenames -- see
# uv-patcher/src/main/resources/airvision-renames.json (kept in lockstep
# with this file; any rename added here must be mirrored there).
#
# - log4j 2.1 -> 2.26.0:                closes the original Log4Shell set
#                                       (CVE-2017-5645, CVE-2021-44228 [Log4Shell],
#                                       -45046, -45105, -44832) AND the Phase 4
#                                       set (CVE-2025-68161, CVE-2026-34477,
#                                       CVE-2026-34479, CVE-2026-34480).
#                                       Phase 3.1 sourced log4j 2.19.0 from
#                                       apt's liblog4j2-java; Phase 4
#                                       (v3.10.13-16) reverted to Maven
#                                       Central at 2.26.0 because apt's
#                                       liblog4j2-java is pinned at 2.19.0 in
#                                       BOTH noble AND the new resolute LTS
#                                       (26.04) and Canonical has not
#                                       refreshed it.  When apt catches up to
#                                       2.25+ we can move back.
# - commons-collections 3.2 -> 3.2.2:   closes CVE-2015-6420, CVE-2015-7501.
# - commons-io 2.6 -> 2.18.0:           closes CVE-2024-47554.
# - commons-beanutils 1.7.0 -> 1.11.0:  closes CVE-2019-10086, CVE-2025-48734.
# - jackson-databind 2.7.4 -> 2.12.7.2: closes ALL 52 historical databind CVEs.
# - jackson-core 2.7.4 -> 2.21.3:       closes CVE-2025-52999 (Phase 2A.1
#                                       in v3.10.13-12 bumped to 2.15.4;
#                                       Phase 4 in v3.10.13-16 bumped to
#                                       2.19.0 to close GHSA-72hv-8253-57qq).
#                                       v3.10.13-17 follow-up: GHSA-72hv-8253
#                                       -57qq was re-scored to also cover
#                                       2.19.0..<2.21.1, so bump to 2.21.3
#                                       (current latest 2.x on Maven Central).
# - jackson-annotations 2.7.2 -> 2.12.7: lockstep with databind.
# - jettison 1.1 -> 1.5.4:              closes CVE-2022-40150, -45685, -45693,
#                                       CVE-2023-1436.
# - json-sanitizer 1.1 -> 1.2.3:        closes CVE-2021-23899, -23900.
# - owasp-java-html-sanitizer r239 ->   closes CVE-2021-42575 (was Phase 1B)
#   20260101.1:                         AND CVE-2025-66021 (the Phase 3 target;
#                                       fix is Java 10 bytecode -- unblocked by
#                                       OpenJDK 21).
# - tomcat-embed-core 7.0.86 ->         closes 16 tomcat CVEs (CVE-2018-1336,
#   9.0.118 (pristine; runtime-patched   -8014, -8034; CVE-2019-0232, -12418,
#   by uv-patcher to add 2 instance     -17563; CVE-2020-1938 [Ghostcat],
#   methods Tomcat 9 removed but        -9484; CVE-2021-25329; CVE-2026-24880,
#   airvision still calls).             -41284, -41293, -42498, -43512, -43513,
#                                       -43515).
# - tomcat-embed-el / -jasper /         in lockstep with -embed-core (Tomcat
#   -embed-websocket 7.0.86 -> 9.0.118  9.x requires same-version companions).
#   (also renames -websocket from the
#   legacy 'tomcat7-embed-websocket'
#   filename to 'tomcat-embed-websocket-9.0.118').
# - tomcat-dbcp 7.0.86 -> 9.0.118:      bump previously blocked by Java 8 cap
#                                       (9.0.118's dbcp is Java 9 bytecode);
#                                       OpenJDK 21 unblocks it.  airvision uses
#                                       MongoDB not JDBC, so no Trivy CVEs were
#                                       reported against 7.0.86; the bump keeps
#                                       the inventory consistent.
# - tomcat-embed-logging-juli.jar:      REMOVED (no Tomcat 9 equivalent; juli
#                                       classes ship inside tomcat-embed-core
#                                       in 9.x).
# - tomcat-embed-logging-log4j.jar:     REMOVED (no Tomcat 9 equivalent; airvision
#                                       uses log4j 2.26.0 directly; bridge unused).
# - httpclient 4.5.1 -> 4.5.14:         closes CVE-2020-13956 (incorrect handling
#                                       of malformed authority component in
#                                       request URIs).  4.5.x is ABI-stable
#                                       within minor; drop-in.  Phase 4 bump.
# - jbcrypt 0.3m -> 0.4:                closes CVE-2015-0886 (integer overflow
#                                       in crypt_raw).  0.4 adds bounds checking
#                                       on log_rounds; the BCrypt.hashpw /
#                                       checkpw / gensalt API is unchanged and
#                                       the crypto output is byte-identical for
#                                       any (password, salt) input -- existing
#                                       bcrypt hashes in the user DB remain
#                                       valid.  Phase 4 bump.
COPY --from=fetcher /artifacts/log4j-api-2.26.0.jar                          /tmp/log4j-api-2.26.0.jar
COPY --from=fetcher /artifacts/log4j-core-2.26.0.jar                         /tmp/log4j-core-2.26.0.jar
COPY --from=fetcher /artifacts/log4j-1.2-api-2.26.0.jar                      /tmp/log4j-1.2-api-2.26.0.jar
COPY --from=fetcher /artifacts/log4j-slf4j-impl-2.26.0.jar                   /tmp/log4j-slf4j-impl-2.26.0.jar
COPY --from=fetcher /artifacts/commons-io-2.18.0.jar                         /tmp/commons-io-2.18.0.jar
COPY --from=fetcher /artifacts/commons-beanutils-1.11.0.jar                  /tmp/commons-beanutils-1.11.0.jar
COPY --from=fetcher /artifacts/jackson-databind-2.12.7.2.jar                 /tmp/jackson-databind-2.12.7.2.jar
COPY --from=fetcher /artifacts/jackson-core-2.21.3.jar                       /tmp/jackson-core-2.21.3.jar
COPY --from=fetcher /artifacts/jackson-annotations-2.12.7.jar                /tmp/jackson-annotations-2.12.7.jar
COPY --from=fetcher /artifacts/json-sanitizer-1.2.3.jar                      /tmp/json-sanitizer-1.2.3.jar
COPY --from=fetcher /artifacts/owasp-java-html-sanitizer-20260101.1.jar      /tmp/owasp-java-html-sanitizer-20260101.1.jar
COPY --from=fetcher /artifacts/tomcat-embed-core-9.0.118.jar                 /tmp/tomcat-embed-core-9.0.118.jar
COPY --from=fetcher /artifacts/tomcat-embed-el-9.0.118.jar                   /tmp/tomcat-embed-el-9.0.118.jar
COPY --from=fetcher /artifacts/tomcat-embed-jasper-9.0.118.jar               /tmp/tomcat-embed-jasper-9.0.118.jar
COPY --from=fetcher /artifacts/tomcat-embed-websocket-9.0.118.jar            /tmp/tomcat-embed-websocket-9.0.118.jar
COPY --from=fetcher /artifacts/tomcat-dbcp-9.0.118.jar                       /tmp/tomcat-dbcp-9.0.118.jar
COPY --from=fetcher /artifacts/httpclient-4.5.14.jar                         /tmp/httpclient-4.5.14.jar
COPY --from=fetcher /artifacts/jbcrypt-0.4.jar                               /tmp/jbcrypt-0.4.jar
RUN set -eux; \
    cd /usr/lib/unifi-video/lib; \
    \
    # Phase 3.1: commons-collections and jettison come from apt's \
    # /usr/share/java tree.  We install -m 400 copies (not symlinks) so the \
    # lib/ tree stays self-contained and Trivy fingerprints the bytes directly. \
    # Phase 4: log4j (all four jars: api / core / 1.2-api / slf4j-impl) is \
    # now Maven-Central-sourced at 2.26.0 instead of apt's pinned 2.19.0 -- \
    # see fetcher-stage comment block for rationale. \
    install -m 400 -o unifi-video -g unifi-video /tmp/log4j-api-2.26.0.jar                          ./log4j-api-2.26.0.jar; \
    install -m 400 -o unifi-video -g unifi-video /tmp/log4j-core-2.26.0.jar                         ./log4j-core-2.26.0.jar; \
    install -m 400 -o unifi-video -g unifi-video /tmp/log4j-1.2-api-2.26.0.jar                      ./log4j-1.2-api-2.26.0.jar; \
    install -m 400 -o unifi-video -g unifi-video /tmp/log4j-slf4j-impl-2.26.0.jar                   ./log4j-slf4j-impl-2.26.0.jar; \
    install -m 400 -o unifi-video -g unifi-video /usr/share/java/commons-collections3-3.2.2.jar     ./commons-collections-3.2.2.jar; \
    install -m 400 -o unifi-video -g unifi-video /usr/share/java/jettison-1.5.4.jar                 ./jettison-1.5.4.jar; \
    \
    # Phase 3.2: JAXB + JAF runtime (removed from JDK in Java 11).  The \
    # patcher appends these filenames to airvision.jar's Manifest Class-Path \
    # attribute so the Guice filter's annotation processing can resolve \
    # javax.xml.bind.JAXBContext and javax.activation.DataSource at runtime. \
    install -m 400 -o unifi-video -g unifi-video /usr/share/java/jaxb-api-2.3.1.jar                 ./jaxb-api-2.3.1.jar; \
    install -m 400 -o unifi-video -g unifi-video /usr/share/java/jaxb-core-2.3.0.1.jar              ./jaxb-core-2.3.0.1.jar; \
    install -m 400 -o unifi-video -g unifi-video /usr/share/java/jaxb-runtime-2.3.0.1.jar           ./jaxb-runtime-2.3.0.1.jar; \
    install -m 400 -o unifi-video -g unifi-video /usr/share/java/javax.activation-1.2.0.jar         ./javax.activation-1.2.0.jar; \
    install -m 400 -o unifi-video -g unifi-video /usr/share/java/istack-commons-runtime-3.0.6.jar   ./istack-commons-runtime-3.0.6.jar; \
    install -m 400 -o unifi-video -g unifi-video /usr/share/java/stax-ex-1.7.8.jar                  ./stax-ex-1.7.8.jar; \
    install -m 400 -o unifi-video -g unifi-video /usr/share/java/txw2-2.3.0.1.jar                   ./txw2-2.3.0.1.jar; \
    \
    # The remaining libraries are still Maven-Central fetched (apt versions \
    # are either too old to close our target CVEs, or the package isn't \
    # present in noble). \
    install -m 400 -o unifi-video -g unifi-video /tmp/commons-io-2.18.0.jar                               ./commons-io-2.18.0.jar; \
    install -m 400 -o unifi-video -g unifi-video /tmp/commons-beanutils-1.11.0.jar                        ./commons-beanutils-1.11.0.jar; \
    install -m 400 -o unifi-video -g unifi-video /tmp/jackson-databind-2.12.7.2.jar                       ./jackson-databind-2.12.7.2.jar; \
    install -m 400 -o unifi-video -g unifi-video /tmp/jackson-core-2.21.3.jar                             ./jackson-core-2.21.3.jar; \
    install -m 400 -o unifi-video -g unifi-video /tmp/jackson-annotations-2.12.7.jar                      ./jackson-annotations-2.12.7.jar; \
    install -m 400 -o unifi-video -g unifi-video /tmp/json-sanitizer-1.2.3.jar                            ./json-sanitizer-1.2.3.jar; \
    install -m 400 -o unifi-video -g unifi-video /tmp/owasp-java-html-sanitizer-20260101.1.jar            ./owasp-java-html-sanitizer-20260101.1.jar; \
    install -m 400 -o unifi-video -g unifi-video /tmp/tomcat-embed-core-9.0.118.jar                       ./tomcat-embed-core-9.0.118.jar; \
    install -m 400 -o unifi-video -g unifi-video /tmp/tomcat-embed-el-9.0.118.jar                         ./tomcat-embed-el-9.0.118.jar; \
    install -m 400 -o unifi-video -g unifi-video /tmp/tomcat-embed-jasper-9.0.118.jar                     ./tomcat-embed-jasper-9.0.118.jar; \
    install -m 400 -o unifi-video -g unifi-video /tmp/tomcat-embed-websocket-9.0.118.jar                  ./tomcat-embed-websocket-9.0.118.jar; \
    install -m 400 -o unifi-video -g unifi-video /tmp/tomcat-dbcp-9.0.118.jar                             ./tomcat-dbcp-9.0.118.jar; \
    install -m 400 -o unifi-video -g unifi-video /tmp/httpclient-4.5.14.jar                               ./httpclient-4.5.14.jar; \
    install -m 400 -o unifi-video -g unifi-video /tmp/jbcrypt-0.4.jar                                     ./jbcrypt-0.4.jar; \
    \
    # Remove the original .deb-installed legacy filenames (and any .jar~ \
    # backup leftovers from earlier image layers).  Each rm corresponds to \
    # an entry in uv-patcher/src/main/resources/airvision-renames.json: \
    # the patcher rewrites airvision.jar's Class-Path at runtime to reference \
    # the new filenames installed above. \
    rm -f \
        log4j-api-2.1.jar \
        log4j-core-2.1.jar \
        log4j-slf4j-impl-2.1.jar \
        commons-collections.jar \
        commons-io-2.6.jar \
        commons-beanutils.jar \
        jackson-databind-2.7.4.jar \
        jackson-core-2.7.4.jar \
        jackson-annotations-2.7.2.jar \
        jettison-1.1.jar \
        json-sanitizer-1.1.jar \
        owasp-java-html-sanitizer-r239.jar \
        tomcat-embed-core.jar \
        tomcat-embed-el.jar \
        tomcat-embed-jasper.jar \
        tomcat7-embed-websocket.jar \
        tomcat-dbcp.jar \
        tomcat-embed-logging-juli.jar \
        tomcat-embed-logging-log4j.jar \
        httpclient-4.5.1.jar \
        jbcrypt-0.3m.jar \
        log4j-api-2.19.0.jar \
        log4j-core-2.19.0.jar \
        log4j-1.2-api-2.19.0.jar \
        log4j-slf4j-impl-2.19.0.jar \
        jackson-core-2.19.0.jar \
        ./*.jar~; \
    \
    # Cleanup tmp scratch. \
    rm -rf /tmp/*.jar

# -------- uv-patcher runtime tool ------------------------------------------
# COPY in the shaded jar + the airvision rename spec.  run.sh invokes the
# patcher before launching jsvc; see uv-patcher/README.md.  v3.10.13-15
# retired the separate tomcat-bootstrap-shim.json -- the Bootstrap call-site
# rewrite is now part of the airvision pass.
COPY --from=patcher-builder /build/target/uv-patcher.jar                            /opt/uv-patcher/uv-patcher.jar
COPY uv-patcher/src/main/resources/airvision-renames.json                           /opt/uv-patcher/airvision-renames.json
RUN chmod 0444 /opt/uv-patcher/uv-patcher.jar \
               /opt/uv-patcher/airvision-renames.json

# -------- Entrypoint scripts -----------------------------------------------
COPY run.sh /run.sh
COPY migrate-mongo.sh /migrate-mongo.sh
RUN chmod 755 /run.sh /migrate-mongo.sh

# -------- Ports (full pducharme + vuhuy + UDP set) -------------------------
# 1935/tcp   RTMP
# 6666/tcp   Inbound camera streams
# 7004/udp   UVC-Micro talkback
# 7080/tcp   HTTP web UI
# 7442/tcp   Camera management (NVR side)
# 7443/tcp   HTTPS web UI + API
# 7444/tcp   RTMPS
# 7445/tcp   Video over HTTP
# 7446/tcp   Video over HTTPS
# 7447/tcp   RTSP
# 10001/udp  Camera discovery (Inform)
EXPOSE 1935/tcp 6666/tcp 7004/udp 7080/tcp 7442/tcp 7443/tcp \
       7444/tcp 7445/tcp 7446/tcp 7447/tcp 10001/udp

# -------- Healthcheck ------------------------------------------------------
# 240s start period covers worst-case cold start: uv-patcher run + fresh DB
# init + one-time 4.0 -> 4.2 -> 4.4 fCV migration.  After that, /api/server
# reachable on 7080.
HEALTHCHECK --start-period=240s --interval=30s --timeout=10s --retries=3 \
    CMD curl -fsk https://localhost:7443/ >/dev/null 2>&1 || exit 1

# -------- Entry ------------------------------------------------------------
# tini is PID 1 so SIGTERM propagates correctly to the unifi-video children
# (jsvc-managed JVM + bundled mongod) for clean shutdowns.
ENTRYPOINT ["/usr/bin/tini", "--"]
CMD ["/run.sh"]
