#!/bin/sh
set -euo pipefail

repository_root="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
env_file="${1:-$repository_root/.env.production}"
public_base_url="${2:-}"
compose_file="$repository_root/docker-compose.prod.yml"
docker_bin="${DOCKER_BIN:-docker}"

if [ ! -r "$env_file" ]; then
    echo "deploy failed: production env file is not readable" >&2
    exit 2
fi
if [ -z "$public_base_url" ]; then
    echo "Usage: deploy.sh [env-file] https://map.example.com" >&2
    exit 2
fi
case "$public_base_url" in
    https://*) ;;
    *) echo "deploy failed: public base URL must use HTTPS" >&2; exit 2 ;;
esac

compose() {
    "$docker_bin" compose --env-file "$env_file" -f "$compose_file" "$@"
}

compose --profile ops config --quiet
for image in $(compose --profile ops config --images); do
    case "$image" in
        *:latest|*:<none>)
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

flyway_version="$(compose exec -T postgres sh -c \
    'psql -U "$(cat /run/secrets/db_username)" -d "$POSTGRES_DB" -Atc "SELECT max(version) FROM flyway_schema_history WHERE success"')"
if [ "$flyway_version" != "3" ]; then
    echo "deploy failed: expected Flyway schema 3, found $flyway_version" >&2
    exit 1
fi

curl --fail --silent --show-error "$public_base_url/health" >/dev/null
curl --fail --silent --show-error "$public_base_url/api/v1/meta" >/dev/null

server_container="$(compose ps -q shared-map-server)"
server_image_id="$("$docker_bin" inspect --format '{{.Image}}' "$server_container")"
server_image_ref="$("$docker_bin" inspect --format '{{.Config.Image}}' "$server_container")"
echo "deploy_status=success"
echo "server_image_ref=$server_image_ref"
echo "server_image_id=$server_image_id"
echo "flyway_version=$flyway_version"
