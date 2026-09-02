#!/bin/sh
set -euo pipefail

require_value() {
    name="$1"
    eval "value=\${$name:-}"
    if [ -z "$value" ]; then
        echo "backup failed: $name is required" >&2
        exit 2
    fi
}

require_file() {
    name="$1"
    eval "path=\${$name:-}"
    if [ -z "$path" ] || [ ! -r "$path" ] || [ ! -s "$path" ]; then
        echo "backup failed: $name must reference a readable, non-empty file" >&2
        exit 2
    fi
}

validate_root() {
    case "$1" in
        ""|"/"|"/backups")
            echo "backup failed: unsafe backup root" >&2
            exit 2
            ;;
    esac
}

is_positive_integer() {
    case "$1" in
        ""|*[!0-9]*|0) return 1 ;;
        *) return 0 ;;
    esac
}

copy_verified() {
    source_dir="$1"
    destination_dir="$2"
    artifact_name="$3"
    mkdir -p "$destination_dir"
    cp "$source_dir/$artifact_name" "$destination_dir/$artifact_name.tmp"
    mv "$destination_dir/$artifact_name.tmp" "$destination_dir/$artifact_name"
    cp "$source_dir/$artifact_name.sha256" "$destination_dir/$artifact_name.sha256.tmp"
    mv "$destination_dir/$artifact_name.sha256.tmp" "$destination_dir/$artifact_name.sha256"
    (cd "$destination_dir" && sha256sum -c "$artifact_name.sha256" >/dev/null)
}

prune_set() {
    directory="$1"
    keep="$2"
    index=0
    find "$directory" -type f -name 'eve-shared-map-*.dump.gpg' | sort -r | while IFS= read -r artifact; do
        index=$((index + 1))
        if [ "$index" -gt "$keep" ]; then
            rm -f -- "$artifact" "$artifact.sha256"
        fi
    done
}

for name in \
    SHARED_MAP_DATABASE_HOST \
    SHARED_MAP_DATABASE_PORT \
    SHARED_MAP_DATABASE_NAME \
    SHARED_MAP_BACKUP_STAGING_DIR \
    SHARED_MAP_BACKUP_OFFSITE_DIR; do
    require_value "$name"
done
for name in \
    SHARED_MAP_DATABASE_USER_FILE \
    SHARED_MAP_DATABASE_PASSWORD_FILE \
    SHARED_MAP_BACKUP_ENCRYPTION_PASSPHRASE_FILE; do
    require_file "$name"
done

daily_retention="${SHARED_MAP_BACKUP_DAILY_RETENTION:-30}"
monthly_retention="${SHARED_MAP_BACKUP_MONTHLY_RETENTION:-12}"
is_positive_integer "$daily_retention" || { echo "backup failed: daily retention must be positive" >&2; exit 2; }
is_positive_integer "$monthly_retention" || { echo "backup failed: monthly retention must be positive" >&2; exit 2; }
validate_root "$SHARED_MAP_BACKUP_STAGING_DIR"
validate_root "$SHARED_MAP_BACKUP_OFFSITE_DIR"

timestamp="${SHARED_MAP_BACKUP_TIMESTAMP:-$(date -u +%Y%m%dT%H%M%SZ)}"
case "$timestamp" in
    [0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]T[0-9][0-9][0-9][0-9][0-9][0-9]Z) ;;
    *) echo "backup failed: timestamp must use YYYYMMDDTHHMMSSZ" >&2; exit 2 ;;
esac

artifact="eve-shared-map-$timestamp.dump.gpg"
staging_daily="$SHARED_MAP_BACKUP_STAGING_DIR/daily"
staging_monthly="$SHARED_MAP_BACKUP_STAGING_DIR/monthly"
offsite_daily="$SHARED_MAP_BACKUP_OFFSITE_DIR/daily"
offsite_monthly="$SHARED_MAP_BACKUP_OFFSITE_DIR/monthly"
mkdir -p "$staging_daily" "$staging_monthly" "$offsite_daily" "$offsite_monthly" "$GNUPGHOME"
chmod 700 "$GNUPGHOME"
umask 077

dump_tmp="/tmp/$artifact.plain.tmp"
encrypted_tmp="/tmp/$artifact.encrypted.tmp"
cleanup() {
    rm -f -- "$dump_tmp" "$encrypted_tmp"
    unset PGPASSWORD database_password
}
trap cleanup EXIT HUP INT TERM

database_user="$(cat "$SHARED_MAP_DATABASE_USER_FILE")"
database_password="$(cat "$SHARED_MAP_DATABASE_PASSWORD_FILE")"
export PGPASSWORD="$database_password"
pg_dump \
    --host "$SHARED_MAP_DATABASE_HOST" \
    --port "$SHARED_MAP_DATABASE_PORT" \
    --username "$database_user" \
    --dbname "$SHARED_MAP_DATABASE_NAME" \
    --format custom \
    --compress 9 \
    --no-owner \
    --no-privileges \
    --file "$dump_tmp"
unset PGPASSWORD database_password

[ -s "$dump_tmp" ] || { echo "backup failed: pg_dump produced an empty artifact" >&2; exit 1; }
pg_restore --list "$dump_tmp" >/dev/null
gpg \
    --batch \
    --yes \
    --pinentry-mode loopback \
    --passphrase-file "$SHARED_MAP_BACKUP_ENCRYPTION_PASSPHRASE_FILE" \
    --symmetric \
    --cipher-algo AES256 \
    --compress-algo none \
    --output "$encrypted_tmp" \
    "$dump_tmp"
[ -s "$encrypted_tmp" ] || { echo "backup failed: encryption produced an empty artifact" >&2; exit 1; }

mv "$encrypted_tmp" "$staging_daily/$artifact"
(cd "$staging_daily" && sha256sum "$artifact" >"$artifact.sha256")
(cd "$staging_daily" && sha256sum -c "$artifact.sha256" >/dev/null)
copy_verified "$staging_daily" "$offsite_daily" "$artifact"

monthly_mode="${SHARED_MAP_BACKUP_MONTHLY:-auto}"
month_key="$(printf '%s' "$timestamp" | cut -c1-6)"
monthly_exists=0
if find "$offsite_monthly" -type f -name "eve-shared-map-$month_key*.dump.gpg" | grep -q .; then
    monthly_exists=1
fi
if [ "$monthly_mode" = "1" ] || { [ "$monthly_mode" = "auto" ] && [ "$monthly_exists" -eq 0 ]; }; then
    copy_verified "$staging_daily" "$staging_monthly" "$artifact"
    copy_verified "$staging_daily" "$offsite_monthly" "$artifact"
fi

prune_set "$staging_daily" "$daily_retention"
prune_set "$staging_monthly" "$monthly_retention"
prune_set "$offsite_daily" "$daily_retention"
prune_set "$offsite_monthly" "$monthly_retention"

checksum="$(sha256sum "$staging_daily/$artifact" | awk '{print $1}')"
echo "backup_status=success"
echo "backup_timestamp=$timestamp"
echo "backup_sha256=$checksum"
echo "backup_staging_artifact=$staging_daily/$artifact"
echo "backup_offsite_artifact=$offsite_daily/$artifact"
