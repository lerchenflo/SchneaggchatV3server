#!/usr/bin/env bash
#
# Runs the tests against throwaway Mongo and Redis containers. Never touches ./mongodb_data or the
# docker-compose stack, and removes everything it started when it ends.
#
#   scripts/docker-test/run.sh          unit tests, then the end-to-end test
#   scripts/docker-test/run.sh unit     only ./gradlew test (contextLoads needs the containers)
#   scripts/docker-test/run.sh e2e      only the end-to-end test
#
# On macOS it starts Docker Desktop when the daemon is not running, and leaves it running.

set -euo pipefail

MODE="${1:-all}"
case "$MODE" in all|unit|e2e) ;; *) echo "Usage: $0 [all|unit|e2e]" >&2; exit 2 ;; esac

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
HERE="$ROOT/scripts/docker-test"

MONGO_CONTAINER=schneaggchat-test-mongo
REDIS_CONTAINER=schneaggchat-test-redis
MONGO_PORT=27117
REDIS_PORT=6479
SERVER_PORT=18080
SERVER_LOG="$(mktemp -t schneaggchat-e2e)"

export SERVER_PORT
export SPRING_DATA_MONGODB_URI="mongodb://127.0.0.1:$MONGO_PORT/db"
export SPRING_DATA_REDIS_HOST=127.0.0.1
export SPRING_DATA_REDIS_PORT="$REDIS_PORT"
export JWT_SECRET='local-test-secret-with-at-least-32-bytes-of-entropy!'
export DEFAULTACCOUNT_PASSWORD='TestPass123!'
export EMAIL=test@test.local
export EMAIL_PASSWORD=unused
export APNS_TEAM_ID=unused
export APNS_KEY_ID=unused
export APNS_BUNDLE_ID=unused
# Debug mode creates the "testaccount" user on startup
export APNS_DEBUG=true

cleanup() {
    lsof -ti "tcp:$SERVER_PORT" 2>/dev/null | xargs kill 2>/dev/null || true
    docker rm -f "$MONGO_CONTAINER" "$REDIS_CONTAINER" >/dev/null 2>&1 || true
    rm -f "$SERVER_LOG"
}
trap cleanup EXIT

mongo_eval() {
    docker exec "$MONGO_CONTAINER" mongosh --quiet db --eval "$1"
}

# docker info can hang forever while Docker Desktop is shutting down, so it gets a hard limit
docker_ready() {
    docker info >/dev/null 2>&1 &
    local check=$!
    ( sleep 5; kill -9 "$check" 2>/dev/null ) &
    local limit=$!
    local result=0
    wait "$check" 2>/dev/null || result=1
    kill "$limit" 2>/dev/null || true
    wait "$limit" 2>/dev/null || true
    return "$result"
}

wait_for_docker() {
    docker_ready && return

    if [[ "$(uname)" == Darwin ]]; then
        echo "Starting Docker Desktop..."
        open -a Docker
    fi

    for _ in $(seq 1 60); do
        docker_ready && return
        sleep 2
    done

    echo "Docker does not answer. Restart Docker Desktop and try again." >&2
    exit 1
}

start_containers() {
    docker rm -f "$MONGO_CONTAINER" "$REDIS_CONTAINER" >/dev/null 2>&1 || true
    docker run -d --name "$MONGO_CONTAINER" -p "127.0.0.1:$MONGO_PORT:27017" mongo:latest >/dev/null
    docker run -d --name "$REDIS_CONTAINER" -p "127.0.0.1:$REDIS_PORT:6379" redis:7-alpine >/dev/null

    for _ in $(seq 1 60); do
        mongo_eval 'db.runCommand({ping: 1})' >/dev/null 2>&1 && return
        sleep 1
    done

    echo "Mongo did not come up." >&2
    exit 1
}

run_unit_tests() {
    echo "== Unit tests"
    # cleanTest: Gradle would skip an unchanged test run, and the index check below needs the startup
    # that creates the annotated indexes
    "$ROOT/gradlew" -p "$ROOT" cleanTest test --console=plain 2>&1 | grep -E '^e:|FAILED|tests completed|BUILD'
    [[ "${PIPESTATUS[0]}" -eq 0 ]]

    echo "== Unique email index"
    local email_unique
    email_unique="$(mongo_eval 'print(db.users.getIndexes().some(i => i.name === "email" && i.unique === true))')"
    if [[ "$email_unique" != true ]]; then
        echo "FAIL users.email is not unique" >&2
        exit 1
    fi
    echo "PASS users.email is unique"
}

run_e2e_test() {
    mongo_eval 'db.dropDatabase()' >/dev/null

    echo "== End-to-end test"
    "$ROOT/gradlew" -p "$ROOT" bootRun --console=plain >"$SERVER_LOG" 2>&1 &

    if ! python3 -I "$HERE/e2e.py" "$SERVER_LOG" "$MONGO_CONTAINER" "$SERVER_PORT" "$DEFAULTACCOUNT_PASSWORD"; then
        echo "Last server log lines:" >&2
        tail -n 20 "$SERVER_LOG" | cut -c1-200 >&2
        exit 1
    fi
}

wait_for_docker
start_containers

[[ "$MODE" != e2e ]] && run_unit_tests
[[ "$MODE" != unit ]] && run_e2e_test

echo "All done."
