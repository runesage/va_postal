#!/bin/bash
# Claude Code cloud sessions: get the container ready to build Postal, run its tests, and boot test servers.
#   - Maven dependencies cached (the build itself runs on the system JDK 21; the pom targets release 21)
#   - a Temurin JDK 25 in /opt/temurin-25, because Paper 26.x needs Java 25 to run (JAVA25 / JAVA25_HOME)
#   - the Docker daemon running, for ci/start-mariadb.sh and the MariaDB store tests
# Idempotent: the JDK and Maven cache survive in the cached container image, so later starts are quick.
set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
    exit 0
fi

cd "${CLAUDE_PROJECT_DIR:-$(dirname "$0")/../..}"

JDK25=/opt/temurin-25
if [ ! -x "$JDK25/bin/javac" ]; then
    echo "Installing Temurin JDK 25 into $JDK25"
    tmp="$(mktemp -d)"
    curl -fsSL --retry 3 -o "$tmp/jdk.tar.gz" \
        "https://api.adoptium.net/v3/binary/latest/25/ga/linux/x64/jdk/hotspot/normal/eclipse"
    mkdir -p "$JDK25"
    tar -xzf "$tmp/jdk.tar.gz" -C "$JDK25" --strip-components=1
    rm -rf "$tmp"
fi
if [ -n "${CLAUDE_ENV_FILE:-}" ] && ! grep -qs JAVA25_HOME "$CLAUDE_ENV_FILE"; then
    {
        echo "export JAVA25_HOME=$JDK25"
        echo "export JAVA25=$JDK25/bin/java"
    } >> "$CLAUDE_ENV_FILE"
fi

if command -v dockerd >/dev/null && ! docker info >/dev/null 2>&1; then
    echo "Starting the Docker daemon"
    nohup dockerd >/tmp/dockerd.log 2>&1 &
    for _ in $(seq 1 30); do
        docker info >/dev/null 2>&1 && break
        sleep 1
    done
    docker info >/dev/null 2>&1 || echo "Docker daemon didn't start; see /tmp/dockerd.log" >&2
fi

# Download every dependency and plugin the build needs, so later `mvn -o` builds work offline.
mvn -B -q -DskipTests package
