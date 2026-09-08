#!/usr/bin/env bash
set -Eeuo pipefail

INSTALLER_SOURCE_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=ops/lib/eve-map-common.sh
source "$INSTALLER_SOURCE_ROOT/ops/lib/eve-map-common.sh"

EVE_MAP_HOME="${EVE_MAP_HOME:-/opt/eve-shared-map}"
EVE_MAP_CLI_PATH="${EVE_MAP_CLI_PATH:-/usr/local/sbin/eve-map}"
EVE_MAP_OS_RELEASE="${EVE_MAP_OS_RELEASE:-/etc/os-release}"
EVE_MAP_MANIFEST_URL="${EVE_MAP_MANIFEST_URL:-https://github.com/zx0003147/EVE-Shared-Map-Server/releases/latest/download/self-hosted-release.json}"
EVE_MAP_MIN_MEMORY_KIB="${EVE_MAP_MIN_MEMORY_KIB:-2097152}"
EVE_MAP_MIN_DISK_KIB="${EVE_MAP_MIN_DISK_KIB:-20971520}"
INSTALLER_RESUME=0
INSTALLER_MANIFEST_OVERRIDDEN=0

installer_usage() {
    cat <<'EOF'
Usage: sudo ./install.sh [--manifest-url <https-url>] [--resume]

Installs a fresh EVE Map self-hosted instance on Ubuntu 24.04 LTS x86_64.
Existing installations must be updated with: sudo eve-map update
Use --resume only when a previous installer run explicitly reports an incomplete installation.
EOF
}

installer_parse_args() {
    while (( $# > 0 )); do
        case "$1" in
            --manifest-url)
                [[ $# -ge 2 ]] || eve_map_die "--manifest-url requires a value"
                EVE_MAP_MANIFEST_URL="$2"
                INSTALLER_MANIFEST_OVERRIDDEN=1
                shift 2
                ;;
            --resume)
                INSTALLER_RESUME=1
                shift
                ;;
            -h|--help)
                installer_usage
                exit 0
                ;;
            *)
                eve_map_die "unknown installer option: $1"
                ;;
        esac
    done
}

installer_check_root() {
    [[ "${EVE_MAP_EFFECTIVE_UID:-$(id -u)}" == "0" ]] ||
        eve_map_die "run this installer with sudo: sudo ./install.sh"
}

installer_check_platform() {
    [[ -r "$EVE_MAP_OS_RELEASE" ]] || eve_map_die "cannot identify the operating system"
    local os_id os_version architecture
    os_id="$(awk -F= '$1 == "ID" { gsub(/\"/, "", $2); print $2 }' "$EVE_MAP_OS_RELEASE")"
    os_version="$(awk -F= '$1 == "VERSION_ID" { gsub(/\"/, "", $2); print $2 }' "$EVE_MAP_OS_RELEASE")"
    [[ "$os_id" == "ubuntu" && "$os_version" == "24.04" ]] ||
        eve_map_die "unsupported OS: this installer supports Ubuntu 24.04 LTS only"
    architecture="${EVE_MAP_ARCHITECTURE:-$(uname -m)}"
    [[ "$architecture" == "x86_64" ]] ||
        eve_map_die "unsupported architecture: this installer currently supports x86_64 only"
}

installer_check_capacity() {
    local memory_kib available_kib check_path
    memory_kib="${EVE_MAP_MEMORY_KIB:-$(awk '/^MemTotal:/ { print $2 }' /proc/meminfo)}"
    [[ "$memory_kib" =~ ^[0-9]+$ ]] || eve_map_die "unable to determine available memory"
    (( memory_kib >= EVE_MAP_MIN_MEMORY_KIB )) || eve_map_die "at least 2 GiB RAM is required"
    check_path="/"
    [[ -d "$(dirname -- "$EVE_MAP_HOME")" ]] && check_path="$(dirname -- "$EVE_MAP_HOME")"
    available_kib="${EVE_MAP_DISK_KIB:-$(df -Pk "$check_path" | awk 'NR == 2 { print $4 }')}"
    [[ "$available_kib" =~ ^[0-9]+$ ]] || eve_map_die "unable to determine available disk space"
    (( available_kib >= EVE_MAP_MIN_DISK_KIB )) || eve_map_die "at least 20 GiB free disk space is required"
}

installer_check_existing() {
    local marker="$EVE_MAP_HOME/.installer-in-progress"
    if [[ -e "$EVE_MAP_HOME/.installer-state" ]]; then
        eve_map_die "Existing installation detected. Use: sudo eve-map update"
    fi
    if (( INSTALLER_RESUME )); then
        [[ -f "$marker" && ! -L "$marker" ]] ||
            eve_map_die "no installer-managed incomplete installation is available to resume"
        if (( ! INSTALLER_MANIFEST_OVERRIDDEN )); then
            local recorded_manifest_url
            recorded_manifest_url="$(eve_map_read_env releaseManifestUrl "$marker")"
            [[ -z "$recorded_manifest_url" ]] || EVE_MAP_MANIFEST_URL="$recorded_manifest_url"
        fi
        return
    fi
    if [[ -e "$marker" || -e "$EVE_MAP_HOME/.env.production" ]]; then
        eve_map_die "Incomplete installation detected. Resume it with: sudo ./install.sh --resume"
    fi
}

installer_ensure_base_tools() {
    export DEBIAN_FRONTEND=noninteractive
    apt-get update
    apt-get install -y ca-certificates curl gnupg jq unzip gzip openssl iproute2
    eve_map_require_command curl
    eve_map_require_command jq
    eve_map_require_command unzip
    eve_map_require_command gzip
    eve_map_require_command sha256sum
    eve_map_require_command getent
    eve_map_require_command ss
}

installer_check_internet() {
    curl --fail --silent --show-error --location --max-time 15 \
        --proto '=https' --tlsv1.2 https://download.docker.com/linux/ubuntu/gpg --output /dev/null ||
        eve_map_die "internet check failed; verify DNS and outbound HTTPS from this VPS"
}

installer_install_docker() {
    if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
        docker version >/dev/null
        docker info >/dev/null
        return
    fi

    local keyring_tmp architecture codename
    export DEBIAN_FRONTEND=noninteractive
    install -m 0755 -d /etc/apt/keyrings
    keyring_tmp="$(mktemp)"
    curl --fail --silent --show-error --location https://download.docker.com/linux/ubuntu/gpg --output "$keyring_tmp"
    gpg --dearmor --yes --output /etc/apt/keyrings/docker.gpg "$keyring_tmp"
    rm -f -- "$keyring_tmp"
    chmod 0644 /etc/apt/keyrings/docker.gpg
    architecture="$(dpkg --print-architecture)"
    # The supported Ubuntu host always provides this standard file.
    # shellcheck disable=SC1091
    codename="$(. /etc/os-release && printf '%s' "$VERSION_CODENAME")"
    printf 'deb [arch=%s signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu %s stable\n' \
        "$architecture" "$codename" >/etc/apt/sources.list.d/docker.list
    apt-get update
    apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
    systemctl enable --now docker
    docker version >/dev/null
    docker info >/dev/null
    docker compose version >/dev/null
}

installer_check_docker_deployment() {
    if docker ps -a --filter label=com.docker.compose.project=eve-shared-map-prod --format '{{.ID}}' | grep -q .; then
        eve_map_die "Existing installation detected in Docker. Use: sudo eve-map update"
    fi
}

installer_check_ports() {
    local port
    for port in 80 443; do
        if [[ "${EVE_MAP_TEST_PORTS_IN_USE:-}" == *" $port "* ]] ||
            ss -H -ltn "sport = :$port" | grep -q .; then
            eve_map_die "TCP port $port is already in use; stop the conflicting service before installing"
        fi
    done
}

installer_fetch_release() {
    RELEASE_WORK_ROOT="$(mktemp -d)"
    RELEASE_MANIFEST_FILE="$RELEASE_WORK_ROOT/self-hosted-release.json"
    RELEASE_WEB_ARTIFACT_FILE="$RELEASE_WORK_ROOT/eve-map-web.zip"
    if (( INSTALLER_RESUME )) && [[ -r "$EVE_MAP_HOME/release/self-hosted-release.json" ]]; then
        cp -- "$EVE_MAP_HOME/release/self-hosted-release.json" "$RELEASE_MANIFEST_FILE"
    else
        eve_map_download "$EVE_MAP_MANIFEST_URL" "$RELEASE_MANIFEST_FILE"
    fi
    eve_map_release_manifest_load "$RELEASE_MANIFEST_FILE"
    eve_map_download "$RELEASE_WEB_ARTIFACT_URL" "$RELEASE_WEB_ARTIFACT_FILE"
    [[ "$(eve_map_sha256 "$RELEASE_WEB_ARTIFACT_FILE")" == "$RELEASE_WEB_SHA256" ]] ||
        eve_map_die "downloaded Web artifact checksum mismatch"
}

installer_prompt_value() {
    local variable="$1"
    local prompt="$2"
    local value="${!variable:-}"
    while [[ -z "$value" ]]; do
        read -r -p "$prompt: " value
    done
    printf -v "$variable" '%s' "$value"
}

installer_collect_answers() {
    if (( INSTALLER_RESUME )) && [[ -r "$EVE_MAP_HOME/.env.production" ]]; then
        SHARED_MAP_DOMAIN="$(eve_map_read_env SHARED_MAP_DOMAIN "$EVE_MAP_HOME/.env.production")"
        SHARED_MAP_WEB_DOMAIN="$(eve_map_read_env SHARED_MAP_WEB_DOMAIN "$EVE_MAP_HOME/.env.production")"
        SHARED_MAP_ACME_EMAIL="$(eve_map_read_env SHARED_MAP_ACME_EMAIL "$EVE_MAP_HOME/.env.production")"
    fi
    installer_prompt_value SHARED_MAP_DOMAIN "Shared Marker domain (for example markers.example.com)"
    SHARED_MAP_DOMAIN="${SHARED_MAP_DOMAIN,,}"
    eve_map_validate_hostname "$SHARED_MAP_DOMAIN" || eve_map_die "Shared Marker domain is invalid"
    installer_prompt_value SHARED_MAP_WEB_DOMAIN "Web Map domain (for example map.example.com)"
    SHARED_MAP_WEB_DOMAIN="${SHARED_MAP_WEB_DOMAIN,,}"
    eve_map_validate_hostname "$SHARED_MAP_WEB_DOMAIN" || eve_map_die "Web Map domain is invalid"
    [[ "$SHARED_MAP_DOMAIN" != "$SHARED_MAP_WEB_DOMAIN" ]] ||
        eve_map_die "Shared Marker and Web Map must use different hostnames"
    installer_prompt_value SHARED_MAP_ACME_EMAIL "Email for HTTPS certificate notices"
    [[ "$SHARED_MAP_ACME_EMAIL" =~ ^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,63}$ ]] ||
        eve_map_die "ACME email address is invalid"
    installer_prompt_value EVE_MAP_WORKSPACE_NAME "Workspace name"
    installer_prompt_value EVE_MAP_ADMIN_NAME "First Admin display name"
    (( ${#EVE_MAP_WORKSPACE_NAME} <= 80 )) || eve_map_die "Workspace name must be at most 80 characters"
    (( ${#EVE_MAP_ADMIN_NAME} <= 80 )) || eve_map_die "Admin display name must be at most 80 characters"
}

installer_public_ipv4() {
    if [[ -n "${EVE_MAP_PUBLIC_IPV4:-}" ]]; then
        printf '%s\n' "$EVE_MAP_PUBLIC_IPV4"
        return
    fi
    curl --fail --silent --show-error --ipv4 --max-time 15 https://api.ipify.org
}

installer_dns_matches() {
    local hostname="$1"
    local expected_ip="$2"
    if [[ -n "${EVE_MAP_DNS_RESULTS_FILE:-}" ]]; then
        awk -v host="$hostname" '$1 == host { print $2 }' "$EVE_MAP_DNS_RESULTS_FILE" | grep -Fxq "$expected_ip"
        return
    fi
    getent ahostsv4 "$hostname" | awk '{print $1}' | sort -u | grep -Fxq "$expected_ip"
}

installer_dns_guide() {
    local public_ip answer
    public_ip="$(installer_public_ipv4)"
    eve_map_is_ipv4 "$public_ip" || eve_map_die "unable to determine this VPS public IPv4 address"
    printf '\nPlease create these DNS records:\n\n'
    printf 'A   %s   -> %s\n' "$SHARED_MAP_DOMAIN" "$public_ip"
    printf 'A   %s   -> %s\n\n' "$SHARED_MAP_WEB_DOMAIN" "$public_ip"
    while true; do
        read -r -p "Press Enter after DNS is configured (or type q to quit): " answer
        [[ "${answer,,}" != "q" ]] || eve_map_die "installation stopped while waiting for DNS"
        if installer_dns_matches "$SHARED_MAP_DOMAIN" "$public_ip" &&
            installer_dns_matches "$SHARED_MAP_WEB_DOMAIN" "$public_ip"; then
            printf 'DNS is ready.\n'
            return
        fi
        printf 'DNS does not yet resolve both hostnames to %s. Fix the records, wait for propagation, and retry.\n' "$public_ip"
    done
}

installer_create_operator_and_directories() {
    getent group eve-map >/dev/null 2>&1 || groupadd --system eve-map
    if ! getent passwd eve-map >/dev/null 2>&1; then
        useradd --system --gid eve-map --home-dir "$EVE_MAP_HOME" --no-create-home --shell /usr/sbin/nologin eve-map
    fi
    getent group docker >/dev/null 2>&1 && usermod -aG docker eve-map
    install -d -o eve-map -g eve-map -m 0750 "$EVE_MAP_HOME"
    install -d -o root -g eve-map -m 0710 "$EVE_MAP_HOME/secrets"
    install -d -o 70 -g 70 -m 0700 "$EVE_MAP_HOME/backups-local-staging"
    install -d -o 70 -g 70 -m 0700 "$EVE_MAP_HOME/backups-offsite"
    install -d -o root -g eve-map -m 0750 "$EVE_MAP_HOME/web" "$EVE_MAP_HOME/web/releases"
    install -d -o root -g eve-map -m 0750 "$EVE_MAP_HOME/release"
    if (( ! INSTALLER_RESUME )); then
        printf 'installerVersion=%s\nreleaseManifestUrl=%s\n' \
            "$EVE_MAP_INSTALLER_VERSION" "$EVE_MAP_MANIFEST_URL" >"$EVE_MAP_HOME/.installer-in-progress"
        chown root:eve-map "$EVE_MAP_HOME/.installer-in-progress"
        chmod 0640 "$EVE_MAP_HOME/.installer-in-progress"
    fi
}

installer_copy_distribution_files() {
    if (( ! INSTALLER_RESUME )); then
        [[ ! -e "$EVE_MAP_HOME/ops" ]] || eve_map_die "refusing to replace an existing ops directory during fresh install"
        [[ ! -e "$EVE_MAP_CLI_PATH" && ! -L "$EVE_MAP_CLI_PATH" ]] ||
            eve_map_die "refusing to replace an existing eve-map command"
    fi
    install -o root -g eve-map -m 0644 "$INSTALLER_SOURCE_ROOT/docker-compose.prod.yml" "$EVE_MAP_HOME/docker-compose.prod.yml"
    install -d -o root -g eve-map -m 0750 "$EVE_MAP_HOME/ops"
    cp -a -- "$INSTALLER_SOURCE_ROOT/ops/." "$EVE_MAP_HOME/ops/"
    find "$EVE_MAP_HOME/ops" -type d -exec chmod 0750 {} +
    find "$EVE_MAP_HOME/ops" -type f -name '*.sh' -exec chmod 0750 {} +
    chmod 0750 "$EVE_MAP_HOME/ops/eve-map"
    chown -R root:eve-map "$EVE_MAP_HOME/ops"
    install -o root -g eve-map -m 0644 "$RELEASE_MANIFEST_FILE" "$EVE_MAP_HOME/release/self-hosted-release.json"
    ln -sfn "$EVE_MAP_HOME/ops/eve-map" "$EVE_MAP_CLI_PATH"
}

installer_generate_env() {
    local target="$EVE_MAP_HOME/.env.production"
    local temporary="$EVE_MAP_HOME/.env.production.new"
    if [[ -e "$target" ]]; then
        (( INSTALLER_RESUME )) || eve_map_die "refusing to overwrite existing .env.production"
        [[ -f "$target" && ! -L "$target" ]] || eve_map_die "resume found an unsafe .env.production"
        [[ "$(stat -c '%U:%G:%a' "$target")" == "root:eve-map:640" ]] ||
            eve_map_die "resume found incorrect .env.production ownership or permissions"
        [[ "$(eve_map_read_env SHARED_MAP_DOMAIN "$target")" == "$SHARED_MAP_DOMAIN" ]] ||
            eve_map_die "resume domain differs from the protected environment"
        [[ "$(eve_map_read_env SHARED_MAP_WEB_DOMAIN "$target")" == "$SHARED_MAP_WEB_DOMAIN" ]] ||
            eve_map_die "resume Web domain differs from the protected environment"
        [[ "$(eve_map_read_env SHARED_MAP_ALLOWED_ORIGINS "$target")" == "https://$SHARED_MAP_WEB_DOMAIN" ]] ||
            eve_map_die "resume CORS origin differs from the protected Web domain"
        [[ "$(eve_map_read_env SHARED_MAP_SERVER_IMAGE "$target")" == "$RELEASE_SERVER_IMAGE" ]] ||
            eve_map_die "resume Server image differs from the recorded release"
        [[ "$(eve_map_read_env SHARED_MAP_OPS_IMAGE "$target")" == "$RELEASE_OPS_IMAGE" ]] ||
            eve_map_die "resume Ops image differs from the recorded release"
        [[ "$(eve_map_read_env SHARED_MAP_EXPECTED_FLYWAY_VERSION "$target")" == "$RELEASE_FLYWAY_VERSION" ]] ||
            eve_map_die "resume Flyway version differs from the recorded release"
        return
    fi
    umask 077
    cat >"$temporary" <<EOF
SHARED_MAP_DOMAIN=$SHARED_MAP_DOMAIN
SHARED_MAP_WEB_DOMAIN=$SHARED_MAP_WEB_DOMAIN
SHARED_MAP_ACME_EMAIL=$SHARED_MAP_ACME_EMAIL
SHARED_MAP_HTTP_PORT=80
SHARED_MAP_HTTPS_PORT=443
SHARED_MAP_ALLOWED_ORIGINS=https://$SHARED_MAP_WEB_DOMAIN
SHARED_MAP_SERVER_IMAGE=$RELEASE_SERVER_IMAGE
SHARED_MAP_OPS_IMAGE=$RELEASE_OPS_IMAGE
SHARED_MAP_POSTGRES_IMAGE=postgres:18.6-alpine
SHARED_MAP_CADDY_IMAGE=caddy:2.10.2-alpine
SHARED_MAP_DATABASE_NAME=eve_shared_map
SHARED_MAP_POSTGRES_DATABASE_USER_FILE=$EVE_MAP_HOME/secrets/postgres-database-user.txt
SHARED_MAP_POSTGRES_DATABASE_PASSWORD_FILE=$EVE_MAP_HOME/secrets/postgres-database-password.txt
SHARED_MAP_SERVER_DATABASE_USER_FILE=$EVE_MAP_HOME/secrets/server-database-user.txt
SHARED_MAP_SERVER_DATABASE_PASSWORD_FILE=$EVE_MAP_HOME/secrets/server-database-password.txt
SHARED_MAP_TOKEN_PEPPER_FILE=$EVE_MAP_HOME/secrets/token-pepper.txt
SHARED_MAP_BACKUP_PASSPHRASE_FILE=$EVE_MAP_HOME/secrets/backup-passphrase.txt
SHARED_MAP_BACKUP_STAGING_HOST_DIR=$EVE_MAP_HOME/backups-local-staging
SHARED_MAP_BACKUP_OFFSITE_HOST_DIR=$EVE_MAP_HOME/backups-offsite
SHARED_MAP_BACKUP_DAILY_RETENTION=30
SHARED_MAP_BACKUP_MONTHLY_RETENTION=12
SHARED_MAP_EXPECTED_FLYWAY_VERSION=$RELEASE_FLYWAY_VERSION
SHARED_MAP_LOG_LEVEL=INFO
SHARED_MAP_SERVER_MEMORY_LIMIT=768m
SHARED_MAP_SERVER_CPU_LIMIT=1.0
SHARED_MAP_POSTGRES_MEMORY_LIMIT=768m
SHARED_MAP_POSTGRES_CPU_LIMIT=1.0
SHARED_MAP_CADDY_MEMORY_LIMIT=256m
SHARED_MAP_CADDY_CPU_LIMIT=0.5
SHARED_MAP_OPS_MEMORY_LIMIT=512m
SHARED_MAP_OPS_CPU_LIMIT=1.0
EOF
    chown root:eve-map "$temporary"
    chmod 0640 "$temporary"
    mv -- "$temporary" "$target"
}

installer_generate_secrets() {
    local expected_count spec file expected_uid expected_gid expected_mode expected_identity actual_identity
    expected_count="$(find "$EVE_MAP_HOME/secrets" -maxdepth 1 -type f -name '*.txt' | wc -l)"
    if (( INSTALLER_RESUME )) && [[ "$expected_count" == "6" ]]; then
        for spec in \
            postgres-database-user.txt:70:70:400 \
            postgres-database-password.txt:70:70:400 \
            backup-passphrase.txt:70:70:400 \
            server-database-user.txt:10001:10001:400 \
            server-database-password.txt:10001:10001:400 \
            token-pepper.txt:10001:10001:400; do
            IFS=: read -r file expected_uid expected_gid expected_mode <<<"$spec"
            expected_identity="$expected_uid:$expected_gid:$expected_mode"
            [[ -f "$EVE_MAP_HOME/secrets/$file" && -s "$EVE_MAP_HOME/secrets/$file" && ! -L "$EVE_MAP_HOME/secrets/$file" ]] ||
                eve_map_die "resume found a missing, empty, or unsafe secret file: $file"
            actual_identity="$(stat -c '%u:%g:%a' "$EVE_MAP_HOME/secrets/$file")"
            [[ "$actual_identity" == "$expected_identity" ]] ||
                eve_map_die "resume found incorrect secret ownership or permissions: $file"
        done
        return
    fi
    [[ "$expected_count" == "0" ]] || eve_map_die "incomplete secret set detected; do not delete it without operator review"
    SHARED_MAP_OPERATOR_GROUP=eve-map "$EVE_MAP_HOME/ops/generate-secrets.sh" "$EVE_MAP_HOME/secrets" >/dev/null
}

installer_deploy() {
    "$EVE_MAP_HOME/ops/deploy.sh" "$EVE_MAP_HOME/.env.production" \
        "https://$SHARED_MAP_DOMAIN" "https://$SHARED_MAP_WEB_DOMAIN" >/dev/null
}

installer_bootstrap_admin() {
    local output
    if ! output="$(docker compose --env-file "$EVE_MAP_HOME/.env.production" \
        -f "$EVE_MAP_HOME/docker-compose.prod.yml" run --rm --no-deps shared-map-server \
        bootstrap-admin --display-name "$EVE_MAP_ADMIN_NAME" --workspace-name "$EVE_MAP_WORKSPACE_NAME" \
        --invite-ttl 1h 2>&1)"; then
        eve_map_die "first Admin bootstrap failed; inspect Server logs with: sudo eve-map logs"
    fi
    FIRST_ADMIN_INVITE="$(printf '%s\n' "$output" | sed -n 's/^inviteToken=\(esm_inv_[^[:space:]]*\)$/\1/p')"
    [[ -n "$FIRST_ADMIN_INVITE" ]] || eve_map_die "bootstrap completed without returning the one-time invite"
    unset output
}

installer_install_backup_timer() {
    install -o root -g root -m 0644 "$EVE_MAP_HOME/ops/systemd/eve-shared-map-backup.service" /etc/systemd/system/eve-shared-map-backup.service
    install -o root -g root -m 0644 "$EVE_MAP_HOME/ops/systemd/eve-shared-map-backup.timer" /etc/systemd/system/eve-shared-map-backup.timer
    systemctl daemon-reload
    systemctl enable --now eve-shared-map-backup.timer >/dev/null
}

installer_write_state() {
    local temporary="$EVE_MAP_HOME/.installer-state.new"
    umask 077
    cat >"$temporary" <<EOF
installerVersion=$EVE_MAP_INSTALLER_VERSION
selfHostedVersion=$RELEASE_SELF_HOSTED_VERSION
webVersion=$RELEASE_WEB_VERSION
serverVersion=$RELEASE_SERVER_VERSION
releaseManifestUrl=$EVE_MAP_MANIFEST_URL
installedAt=$(date -u +%Y-%m-%dT%H:%M:%SZ)
EOF
    chown root:eve-map "$temporary"
    chmod 0640 "$temporary"
    mv -- "$temporary" "$EVE_MAP_HOME/.installer-state"
    rm -f -- "$EVE_MAP_HOME/.installer-in-progress"
}

installer_cleanup() {
    if [[ -n "${RELEASE_WORK_ROOT:-}" && -d "$RELEASE_WORK_ROOT" ]]; then
        rm -rf -- "$RELEASE_WORK_ROOT"
    fi
}

installer_main() {
    trap installer_cleanup EXIT HUP INT TERM
    installer_parse_args "$@"
    installer_check_root
    installer_check_platform
    installer_check_existing
    installer_check_capacity
    installer_ensure_base_tools
    installer_check_internet
    installer_install_docker
    if (( ! INSTALLER_RESUME )); then
        installer_check_docker_deployment
        installer_check_ports
    fi
    installer_fetch_release
    installer_collect_answers
    installer_dns_guide
    installer_create_operator_and_directories
    installer_copy_distribution_files
    installer_generate_env
    installer_generate_secrets
    eve_map_stage_web_artifact "$RELEASE_WEB_ARTIFACT_FILE" "$RELEASE_WEB_SHA256" "$RELEASE_WEB_VERSION"
    eve_map_switch_web_release "$STAGED_WEB_RELEASE"
    installer_deploy
    installer_install_backup_timer
    installer_bootstrap_admin
    printf '\nFirst Admin Invite — SAVE THIS INVITE NOW; it cannot be shown again:\n%s\n' "$FIRST_ADMIN_INVITE"
    installer_write_state

    printf '\nInstallation completed.\n\n'
    printf 'Web Map:\nhttps://%s\n\n' "$SHARED_MAP_WEB_DOMAIN"
    printf 'Shared Marker:\nhttps://%s\n\n' "$SHARED_MAP_DOMAIN"
    printf 'Workspace:\n%s\n\n' "$EVE_MAP_WORKSPACE_NAME"
    printf 'Useful commands:\n'
    printf '  sudo eve-map status\n'
    printf '  sudo eve-map update\n'
    printf '  sudo eve-map logs\n'
    printf '  sudo eve-map diagnostics\n'
    printf '  sudo eve-map backup\n'
    unset FIRST_ADMIN_INVITE
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    installer_main "$@"
fi
