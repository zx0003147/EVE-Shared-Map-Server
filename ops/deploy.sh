#!/usr/bin/env bash
set -euo pipefail

repository_root="$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)"
env_file="${1:-$repository_root/.env.production}"
public_base_url="${2:-}"
public_web_url="${3:-}"
compose_file="$repository_root/docker-compose.prod.yml"
docker_bin="${DOCKER_BIN:-docker}"
headers_file=""

cleanup() {
    if [ -n "$headers_file" ]; then
        rm -f -- "$headers_file"
    fi
}
trap cleanup EXIT HUP INT TERM

if [ ! -r "$env_file" ]; then
    echo "deploy failed: production env file is not readable" >&2
    exit 2
fi
if [ -z "$public_base_url" ] || [ -z "$public_web_url" ]; then
    echo "Usage: deploy.sh [env-file] https://markers.example.com https://map.example.com" >&2
    exit 2
fi
for public_url in "$public_base_url" "$public_web_url"; do
    case "$public_url" in
        https://*) ;;
        *) echo "deploy failed: public URLs must use HTTPS" >&2; exit 2 ;;
    esac
done

compose() {
    "$docker_bin" compose --env-file "$env_file" -f "$compose_file" "$@"
}

compose --profile ops config --quiet
for image in $(compose --profile ops config --images); do
    case "$image" in
        *:latest|*:"<none>")
            echo "deploy failed: every image must use an explicit non-latest tag or digest ($image)" >&2
            exit 2
            ;;
        *@sha256:*) ;;
        *)
            image_name="${image##*/}"
            case "$image_name" in
                *:*) ;;
                *) echo "deploy failed: image is not pinned ($image)" >&2; exit 2 ;;
            esac
            ;;
    esac
done

compose --profile ops pull --policy missing
compose up -d --no-build postgres shared-map-server caddy

wait_healthy() {
    service="$1"
    attempts=0
    while [ "$attempts" -lt 60 ]; do
        container_id="$(compose ps -q "$service")"
        if [ -n "$container_id" ]; then
            state="$("$docker_bin" inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$container_id")"
            if [ "$state" = "healthy" ]; then
                return 0
            fi
            if [ "$state" = "unhealthy" ] || [ "$state" = "exited" ] || [ "$state" = "dead" ]; then
                echo "deploy failed: $service entered state $state" >&2
                compose logs --tail 100 "$service" >&2
                return 1
            fi
        fi
        attempts=$((attempts + 1))
        sleep 2
    done
    echo "deploy failed: $service did not become healthy" >&2
    return 1
}

wait_healthy postgres
wait_healthy shared-map-server
wait_healthy caddy

# Variables in this command expand intentionally inside the PostgreSQL container.
# shellcheck disable=SC2016
flyway_version="$(compose exec -T postgres sh -c \
    'psql -U "$(cat /run/secrets/db_username)" -d "$POSTGRES_DB" -Atc "SELECT max(version) FROM flyway_schema_history WHERE success"')"
expected_flyway_version="$(awk -F= '$1 == "SHARED_MAP_EXPECTED_FLYWAY_VERSION" { print $2 }' "$env_file" | tail -n 1 | tr -d '\r')"
expected_flyway_version="${expected_flyway_version:-4}"
case "$expected_flyway_version" in
    ''|*[!0-9]*) echo "deploy failed: SHARED_MAP_EXPECTED_FLYWAY_VERSION must be numeric" >&2; exit 2 ;;
esac
if [ "$flyway_version" != "$expected_flyway_version" ]; then
    echo "deploy failed: expected Flyway schema $expected_flyway_version, found $flyway_version" >&2
    exit 1
fi

curl --fail --silent --show-error "$public_base_url/health" >/dev/null
curl --fail --silent --show-error "$public_base_url/api/v1/meta" >/dev/null
curl --fail --silent --show-error "$public_web_url/" >/dev/null
web_manifest="$(curl --fail --silent --show-error "$public_web_url/data/manifest.json")"
pack_file="$(printf '%s' "$web_manifest" | sed -n 's/.*"fileName"[[:space:]]*:[[:space:]]*"\([A-Za-z0-9._-]*\.json\.gz\)".*/\1/p')"
case "$pack_file" in
    web-pack-*.json.gz) ;;
    *) echo "deploy failed: public Web Pack manifest is invalid" >&2; exit 1 ;;
esac
headers_file="$(mktemp)"
curl --fail --silent --show-error --dump-header "$headers_file" --output /dev/null \
    "$public_web_url/data/$pack_file"
if grep -qi '^Content-Encoding:' "$headers_file"; then
    echo "deploy failed: Web Pack must not have Content-Encoding" >&2
    exit 1
fi
curl --fail --silent --show-error --dump-header "$headers_file" --output /dev/null \
    --header "Origin: $public_web_url" \
    "$public_base_url/api/v1/meta"
if ! awk -v expected="$public_web_url" '
    BEGIN { found = 0 }
    tolower($1) == "access-control-allow-origin:" {
        value = $2
        sub(/\r$/, "", value)
        if (value == expected) found = 1
    }
    END { exit(found ? 0 : 1) }
' "$headers_file"; then
    echo "deploy failed: Shared Marker CORS does not allow the exact Web origin" >&2
    exit 1
fi
curl --fail --silent --show-error --output /dev/null --request OPTIONS \
    --header "Origin: $public_web_url" \
    --header 'Access-Control-Request-Method: POST' \
    --header 'Access-Control-Request-Headers: authorization,content-type,x-request-id,idempotency-key' \
    "$public_base_url/api/v1/workspaces/00000000-0000-0000-0000-000000000000/markers"

server_container="$(compose ps -q shared-map-server)"
server_image_id="$("$docker_bin" inspect --format '{{.Image}}' "$server_container")"
server_image_ref="$("$docker_bin" inspect --format '{{.Config.Image}}' "$server_container")"
echo "deploy_status=success"
echo "server_image_ref=$server_image_ref"
echo "server_image_id=$server_image_id"
echo "flyway_version=$flyway_version"
echo "web_url=$public_web_url"
echo "web_pack=$pack_file"
