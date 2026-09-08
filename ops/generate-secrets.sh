#!/usr/bin/env bash
set -euo pipefail

target="${1:-}"
if [ -z "$target" ]; then
    echo "Usage: generate-secrets.sh /opt/eve-shared-map/secrets" >&2
    exit 2
fi
case "$target" in
    ""|"/"|"/opt"|"/opt/eve-shared-map")
        echo "refusing unsafe secrets directory" >&2
        exit 2
        ;;
esac

umask 077
if [ "$(id -u)" -ne 0 ]; then
    echo "generate-secrets.sh must run as root so files can be assigned to container UIDs 70 and 10001" >&2
    exit 1
fi
operator_group="${SHARED_MAP_OPERATOR_GROUP:-eve-map}"
if ! getent group "$operator_group" >/dev/null 2>&1; then
    echo "operator group does not exist: $operator_group" >&2
    exit 1
fi
mkdir -p "$target"
chown root:"$operator_group" "$target"
chmod 0710 "$target"
for file in \
    postgres-database-user.txt \
    postgres-database-password.txt \
    server-database-user.txt \
    server-database-password.txt \
    token-pepper.txt \
    backup-passphrase.txt; do
    if [ -e "$target/$file" ]; then
        echo "refusing to overwrite existing secret file: $file" >&2
        exit 1
    fi
done

database_password="$(openssl rand -base64 48)"
printf '%s\n' 'eve_shared_map' >"$target/postgres-database-user.txt"
printf '%s\n' 'eve_shared_map' >"$target/server-database-user.txt"
printf '%s\n' "$database_password" >"$target/postgres-database-password.txt"
printf '%s\n' "$database_password" >"$target/server-database-password.txt"
unset database_password
openssl rand -base64 48 >"$target/token-pepper.txt"
openssl rand -base64 48 >"$target/backup-passphrase.txt"

chown 70:70 "$target/postgres-database-user.txt" "$target/postgres-database-password.txt" "$target/backup-passphrase.txt"
chown 10001:10001 "$target/server-database-user.txt" "$target/server-database-password.txt" "$target/token-pepper.txt"
chmod 0400 "$target"/*.txt
echo "Created six consumer-specific production secret files in $target."
echo "Back up token-pepper.txt and both database credential copies separately from the database dump."
