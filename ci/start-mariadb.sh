#!/usr/bin/env bash
# Starts a throwaway MariaDB 11 on 127.0.0.1:3306 for CI and waits until it accepts connections.
#
# Usage: ci/start-mariadb.sh <database>
# Creates <database> with user postal / password postal (root password: root).
#
# CI used GitHub service containers for this, but those pull once from one registry with three quick retries,
# and the anonymous pull limits (Docker Hub's, then the ECR mirror's) failed whole runs before any test ran.
# This retries with backoff and falls back to a second registry.
set -euo pipefail

DATABASE="${1:?usage: start-mariadb.sh <database>}"
IMAGES=(public.ecr.aws/docker/library/mariadb:11 docker.io/library/mariadb:11)
NAME=postal-mariadb

pulled=""
for attempt in 1 2 3 4 5; do
    for image in "${IMAGES[@]}"; do
        if docker pull -q "$image"; then
            pulled="$image"
            break 2
        fi
    done
    wait_s=$((attempt * 15))
    echo "Couldn't pull MariaDB from any registry (attempt $attempt); retrying in ${wait_s}s." >&2
    sleep "$wait_s"
done
if [ -z "$pulled" ]; then
    echo "::error::Couldn't pull MariaDB from ${IMAGES[*]}" >&2
    exit 1
fi

docker rm -f "$NAME" >/dev/null 2>&1 || true
docker run -d --name "$NAME" -p 127.0.0.1:3306:3306 \
    -e MARIADB_ROOT_PASSWORD=root -e MARIADB_DATABASE="$DATABASE" \
    -e MARIADB_USER=postal -e MARIADB_PASSWORD=postal \
    "$pulled" >/dev/null

for _ in $(seq 1 60); do
    if docker exec "$NAME" healthcheck.sh --connect --innodb_initialized >/dev/null 2>&1; then
        echo "MariaDB ($pulled) is up with database $DATABASE."
        exit 0
    fi
    sleep 2
done
echo "::error::MariaDB didn't come up within 120s" >&2
docker logs "$NAME" >&2 || true
exit 1
