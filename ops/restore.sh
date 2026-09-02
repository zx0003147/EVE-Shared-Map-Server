#!/bin/sh
set -euo pipefail

usage() {
    cat >&2 <<'EOF'
Usage: restore.sh --backup <encrypted.dump.gpg> --checksum <file.sha256> \
  --target-database <name> --confirm-reset <same-name> [--allow-production-restore]
EOF
}

require_file() {
    name="$1"
    eval "path=\${$name:-}"
    if [ -z "$path" ] || [ ! -r "$path" ] || [ ! -s "$path" ]; then
        echo "restore failed: $name must reference a readable, non-empty file" >&2
        exit 2
    fi
}

require_value() {
    name="$1"
    eval "value=\${$name:-}"
    if [ -z "$value" ]; then
        echo "restore failed: $name is required" >&2
        exit 2
    fi
}

backup=""
checksum_file=""
target_database=""
confirmation=""
allow_production=0
while [ "$#" -gt 0 ]; do
    case "$1" in
        --backup) backup="${2:-}"; shift 2 ;;
        --checksum) checksum_file="${2:-}"; shift 2 ;;
        --target-database) target_database="${2:-}"; shift 2 ;;
        --confirm-reset) confirmation="${2:-}"; shift 2 ;;
        --allow-production-restore) allow_production=1; shift ;;
        *) usage; exit 2 ;;
    esac
done

if [ -z "$backup" ] || [ -z "$checksum_file" ] || [ -z "$target_database" ]; then
    usage
    exit 2
fi
case "$target_database" in
    postgres|template0|template1|""|*[!A-Za-z0-9_]* )
        echo "restore failed: target database name is unsafe" >&2
        exit 2
        ;;
esac
if [ "$confirmation" != "$target_database" ]; then
    echo "restore refused: --confirm-reset must exactly match the target database name" >&2
    exit 2
fi
if [ "${SHARED_MAP_ENVIRONMENT:-validation}" = "production" ] && [ "$allow_production" -ne 1 ]; then
    echo "restore refused: production restore also requires --allow-production-restore" >&2
    exit 2
fi

for name in SHARED_MAP_DATABASE_HOST SHARED_MAP_DATABASE_PORT; do
    require_value "$name"
done
require_file SHARED_MAP_DATABASE_USER_FILE
require_file SHARED_MAP_DATABASE_PASSWORD_FILE
require_file SHARED_MAP_BACKUP_ENCRYPTION_PASSPHRASE_FILE
require_file backup
require_file checksum_file

backup_directory="$(dirname "$backup")"
backup_name="$(basename "$backup")"
checksum_name="$(basename "$checksum_file")"
if [ "$(CDPATH= cd -- "$(dirname "$checksum_file")" && pwd)" != "$(CDPATH= cd -- "$backup_directory" && pwd)" ]; then
    echo "restore failed: checksum must be beside the backup artifact" >&2
    exit 2
fi
checksum_target="$(awk 'NR == 1 { value=$2; sub(/^\*/, "", value); print value }' "$checksum_file")"
if [ "$checksum_target" != "$backup_name" ]; then
    echo "restore failed: checksum does not name the requested backup artifact" >&2
    exit 2
fi
(cd "$backup_directory" && sha256sum -c "$checksum_name" >/dev/null)

mkdir -p "$GNUPGHOME"
chmod 700 "$GNUPGHOME"
umask 077
dump_tmp="/tmp/$backup_name.plain.tmp"
cleanup() {
    rm -f -- "$dump_tmp"
    unset PGPASSWORD database_password
}
trap cleanup EXIT HUP INT TERM

gpg \
    --batch \
    --yes \
    --pinentry-mode loopback \
    --passphrase-file "$SHARED_MAP_BACKUP_ENCRYPTION_PASSPHRASE_FILE" \
    --decrypt \
    --output "$dump_tmp" \
    "$backup"
[ -s "$dump_tmp" ] || { echo "restore failed: decryption produced an empty dump" >&2; exit 1; }
pg_restore --list "$dump_tmp" >/dev/null

database_user="$(cat "$SHARED_MAP_DATABASE_USER_FILE")"
database_password="$(cat "$SHARED_MAP_DATABASE_PASSWORD_FILE")"
export PGPASSWORD="$database_password"
connection_args="--host=$SHARED_MAP_DATABASE_HOST --port=$SHARED_MAP_DATABASE_PORT --username=$database_user"
# shellcheck disable=SC2086
dropdb $connection_args --if-exists --force "$target_database"
# shellcheck disable=SC2086
createdb $connection_args "$target_database"
# shellcheck disable=SC2086
pg_restore $connection_args \
    --dbname "$target_database" \
    --exit-on-error \
    --no-owner \
    --no-privileges \
    "$dump_tmp"

expected_flyway_version="${SHARED_MAP_EXPECTED_FLYWAY_VERSION:-3}"
case "$expected_flyway_version" in
    ""|*[!0-9]*) echo "restore failed: expected Flyway version must be numeric" >&2; exit 2 ;;
esac
# shellcheck disable=SC2086
flyway_state="$(psql $connection_args --dbname "$target_database" --tuples-only --no-align --command \
    "SELECT CASE WHEN bool_and(success) AND max(version::integer) = $expected_flyway_version THEN 'ok' ELSE 'invalid' END FROM flyway_schema_history WHERE version IS NOT NULL;")"
if [ "$flyway_state" != "ok" ]; then
    echo "restore failed: Flyway schema compatibility check did not pass" >&2
    exit 1
fi
# shellcheck disable=SC2086
marker_count="$(psql $connection_args --dbname "$target_database" --tuples-only --no-align --command 'SELECT count(*) FROM shared_markers;')"
# shellcheck disable=SC2086
audit_count="$(psql $connection_args --dbname "$target_database" --tuples-only --no-align --command 'SELECT count(*) FROM audit_events;')"
unset PGPASSWORD database_password

echo "restore_status=success"
echo "restore_target_database=$target_database"
echo "restore_flyway_version=$expected_flyway_version"
echo "restore_marker_count=$marker_count"
echo "restore_audit_count=$audit_count"
