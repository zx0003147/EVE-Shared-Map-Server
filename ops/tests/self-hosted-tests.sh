#!/usr/bin/env bash
# The suite intentionally sources and overrides installer functions.
# shellcheck disable=SC1091,SC2016,SC2030,SC2031,SC2034,SC2329
set -uo pipefail

TEST_ROOT="$(mktemp -d)"
REPOSITORY_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
EVE_MAP_HOME="$TEST_ROOT/home"
EVE_MAP_CLI_PATH="$TEST_ROOT/bin/eve-map"
EVE_MAP_TEST_MODE=1
export EVE_MAP_HOME EVE_MAP_CLI_PATH EVE_MAP_TEST_MODE
mkdir -p "$EVE_MAP_HOME" "$TEST_ROOT/bin"

# shellcheck source=../../install.sh
source "$REPOSITORY_ROOT/install.sh"

passes=0
failures=0

cleanup() {
    rm -rf -- "$TEST_ROOT"
}
trap cleanup EXIT HUP INT TERM

run_test() {
    local name="$1"
    shift
    if "$@"; then
        printf 'PASS %s\n' "$name"
        passes=$((passes + 1))
    else
        printf 'FAIL %s\n' "$name" >&2
        failures=$((failures + 1))
    fi
}

make_web_pack() {
    local data="$1"
    local pack_version="$2"
    local sde_build="${3:-3466501}"
    local pack_name="web-pack-$pack_version.json.gz"
    local size checksum
    mkdir -p "$data"
    printf '{"schemaVersion":1,"packVersion":"%s","sdeBuild":%s}\n' "$pack_version" "$sde_build" |
        gzip -n >"$data/$pack_name"
    size="$(stat -c '%s' "$data/$pack_name")"
    checksum="$(sha256sum "$data/$pack_name" | awk '{print $1}')"
    printf '{"schemaVersion":1,"packVersion":"%s","generatedAt":"2026-09-08T00:00:00Z","desktopAppVersion":"1.7.0","sdeBuild":%s,"fileName":"%s","sizeBytes":%s,"sha256":"%s","counts":{"systems":1,"stargateLinks":1,"regions":1,"constellations":1,"ansiblexLinks":0}}\n' \
        "$pack_version" "$sde_build" "$pack_name" "$size" "$checksum" >"$data/manifest.json"
}

make_web_artifact() {
    local site="$TEST_ROOT/site"
    local artifact="$TEST_ROOT/eve-map-web-1.7.0.zip"
    rm -rf -- "$site"
    mkdir -p "$site/icons"
    for file in index.html web-client.js web-client.css web-pack-loader.mjs pwa-runtime.mjs manifest.webmanifest service-worker.js; do
        printf 'fixture\n' >"$site/$file"
    done
    printf 'png\n' >"$site/icons/app-icon-192.png"
    printf 'png\n' >"$site/icons/app-icon-512.png"
    make_web_pack "$site/data" "3466501-fixture-a"
    printf '{"formatVersion":1,"artifactType":"eve-map-self-hosted-web","webVersion":"1.7.0","webPackSchemaVersion":1,"webPackVersion":"3466501-fixture-a","sdeBuild":3466501}\n' >"$site/self-hosted-web.json"
    jar --create --file "$artifact" -C "$site" .
    WEB_ARTIFACT="$artifact"
    WEB_ARTIFACT_SHA="$(sha256sum "$artifact" | awk '{print $1}')"
}

test_unsupported_os() {
    local os_file="$TEST_ROOT/os-release-unsupported"
    printf 'ID=debian\nVERSION_ID="12"\n' >"$os_file"
    (EVE_MAP_OS_RELEASE="$os_file" installer_check_platform) >/dev/null 2>&1 && return 1
    return 0
}

test_missing_root() {
    (EVE_MAP_EFFECTIVE_UID=1000 installer_check_root) >/dev/null 2>&1 && return 1
    return 0
}

test_existing_install() {
    touch "$EVE_MAP_HOME/.installer-state"
    (installer_check_existing) >/dev/null 2>&1 && return 1
    rm -f -- "$EVE_MAP_HOME/.installer-state"
    return 0
}

test_dns_mismatch() {
    local dns="$TEST_ROOT/dns.txt"
    printf 'map.example.test 203.0.113.10\n' >"$dns"
    EVE_MAP_DNS_RESULTS_FILE="$dns" installer_dns_matches map.example.test 203.0.113.11 >/dev/null 2>&1 && return 1
    EVE_MAP_DNS_RESULTS_FILE="$dns" installer_dns_matches map.example.test 203.0.113.10
}

test_env_generation() {
    local mock_bin="$TEST_ROOT/mock-env-bin"
    local env_mode
    mkdir -p "$mock_bin"
    printf '#!/usr/bin/env bash\nexit 0\n' >"$mock_bin/chown"
    chmod +x "$mock_bin/chown"
    SHARED_MAP_DOMAIN=markers.example.test
    SHARED_MAP_WEB_DOMAIN=map.example.test
    SHARED_MAP_ACME_EMAIL=admin@example.test
    RELEASE_SERVER_IMAGE=registry.example.test/server:0.1.0
    RELEASE_OPS_IMAGE=registry.example.test/ops:0.1.0
    RELEASE_FLYWAY_VERSION=3
    PATH="$mock_bin:$PATH" installer_generate_env
    grep -Fxq 'SHARED_MAP_ALLOWED_ORIGINS=https://map.example.test' "$EVE_MAP_HOME/.env.production" || return 1
    grep -Fxq "SHARED_MAP_TOKEN_PEPPER_FILE=$EVE_MAP_HOME/secrets/token-pepper.txt" "$EVE_MAP_HOME/.env.production" || return 1
    env_mode="$(stat -c '%a' "$EVE_MAP_HOME/.env.production")"
    if [[ "$(uname -s)" == MINGW* ]]; then
        [[ "$env_mode" == "600" || "$env_mode" == "640" ]] || return 1
    else
        [[ "$env_mode" == "640" ]] || return 1
    fi
    ! grep -Eqi '(password|pepper)=.+[^/]$' "$EVE_MAP_HOME/.env.production"
}

test_secret_generation() {
    local mock_bin="$TEST_ROOT/mock-secret-bin"
    local target="$TEST_ROOT/generated-secrets"
    mkdir -p "$mock_bin"
    printf '#!/usr/bin/env bash\n[[ "${1:-}" == "-u" ]] && { echo 0; exit 0; }\nexit 0\n' >"$mock_bin/id"
    printf '#!/usr/bin/env bash\nexit 0\n' >"$mock_bin/getent"
    printf '#!/usr/bin/env bash\nexit 0\n' >"$mock_bin/chown"
    chmod +x "$mock_bin/id" "$mock_bin/getent" "$mock_bin/chown"
    PATH="$mock_bin:$PATH" SHARED_MAP_OPERATOR_GROUP=eve-map \
        bash "$REPOSITORY_ROOT/ops/generate-secrets.sh" "$target" >"$TEST_ROOT/secret-output.txt" || return 1
    [[ "$(find "$target" -maxdepth 1 -type f -name '*.txt' | wc -l)" == "6" ]] || return 1
    cmp -s "$target/postgres-database-password.txt" "$target/server-database-password.txt" || return 1
    [[ "$(stat -c '%a' "$target/token-pepper.txt")" == "400" ]] || return 1
    ! grep -Fq "$(cat "$target/token-pepper.txt")" "$TEST_ROOT/secret-output.txt"
}

test_invalid_release_manifest() {
    local manifest="$TEST_ROOT/invalid-release.json"
    printf '{"schemaVersion":1,"serverImage":"example/server:latest"}\n' >"$manifest"
    (eve_map_release_manifest_load "$manifest") >/dev/null 2>&1 && return 1
    return 0
}

test_valid_release_manifest() {
    make_web_artifact
    local manifest="$TEST_ROOT/valid-release.json"
    cat >"$manifest" <<EOF
{
  "schemaVersion": 1,
  "selfHostedVersion": "0.1.0",
  "webVersion": "1.7.0",
  "serverVersion": "0.1.0",
  "webArtifactUrl": "file://$WEB_ARTIFACT",
  "webSha256": "$WEB_ARTIFACT_SHA",
  "serverImage": "ghcr.io/example/eve-map-server:0.1.0",
  "opsImage": "ghcr.io/example/eve-map-ops:0.1.0",
  "flywayVersion": 3,
  "minimumInstallerVersion": 1
}
EOF
    eve_map_release_manifest_load "$manifest" || return 1
    [[ "$RELEASE_SELF_HOSTED_VERSION" == "0.1.0" ]] || return 1
    [[ "$RELEASE_WEB_VERSION" == "1.7.0" ]] || return 1
    [[ "$RELEASE_FLYWAY_VERSION" == "3" ]] || return 1
    [[ "$RELEASE_SERVER_IMAGE" == "ghcr.io/example/eve-map-server:0.1.0" ]]
}

test_checksum_mismatch() {
    make_web_artifact
    rm -rf -- "$EVE_MAP_HOME/web"
    (eve_map_stage_web_artifact "$WEB_ARTIFACT" "0000000000000000000000000000000000000000000000000000000000000000" 1.7.0) >/dev/null 2>&1 && return 1
    return 0
}

test_missing_web_artifact() {
    local incomplete="$TEST_ROOT/incomplete-site"
    mkdir -p "$incomplete/data"
    (eve_map_validate_web_directory "$incomplete" 1.7.0) >/dev/null 2>&1 && return 1
    return 0
}

test_config_contract() {
    grep -Fq './web:/srv/eve-map:ro' "$REPOSITORY_ROOT/docker-compose.prod.yml" || return 1
    grep -Fq 'SHARED_MAP_WEB_DOMAIN' "$REPOSITORY_ROOT/docker-compose.prod.yml" || return 1
    ! grep -Eq '(^|[[:space:]])ports:.*(5432|8080)' "$REPOSITORY_ROOT/docker-compose.prod.yml"
}

test_install_success_simulation() (
    local calls="$TEST_ROOT/install-calls"
    rm -f -- "$calls"
    installer_check_root() { printf 'root\n' >>"$calls"; }
    installer_check_platform() { printf 'platform\n' >>"$calls"; }
    installer_check_existing() { printf 'existing\n' >>"$calls"; }
    installer_check_capacity() { printf 'capacity\n' >>"$calls"; }
    installer_ensure_base_tools() { printf 'tools\n' >>"$calls"; }
    installer_check_internet() { printf 'internet\n' >>"$calls"; }
    installer_install_docker() { printf 'docker\n' >>"$calls"; }
    installer_check_docker_deployment() { printf 'docker-existing\n' >>"$calls"; }
    installer_check_ports() { printf 'ports\n' >>"$calls"; }
    installer_fetch_release() {
        RELEASE_WORK_ROOT="$TEST_ROOT/mock-release-work"
        mkdir -p "$RELEASE_WORK_ROOT"
        RELEASE_WEB_ARTIFACT_FILE="$RELEASE_WORK_ROOT/web.zip"
        RELEASE_WEB_SHA256="$(printf fixture | sha256sum | awk '{print $1}')"
        RELEASE_WEB_VERSION=1.7.0
        RELEASE_SELF_HOSTED_VERSION=0.1.0
        RELEASE_SERVER_VERSION=0.1.0
        printf fixture >"$RELEASE_WEB_ARTIFACT_FILE"
        printf 'release\n' >>"$calls"
    }
    installer_collect_answers() {
        SHARED_MAP_DOMAIN=markers.example.test
        SHARED_MAP_WEB_DOMAIN=map.example.test
        EVE_MAP_WORKSPACE_NAME=Fixture
        printf 'answers\n' >>"$calls"
    }
    installer_dns_guide() { printf 'dns\n' >>"$calls"; }
    installer_create_operator_and_directories() { printf 'directories\n' >>"$calls"; }
    installer_copy_distribution_files() { printf 'copy\n' >>"$calls"; }
    installer_generate_env() { printf 'env\n' >>"$calls"; }
    installer_generate_secrets() { printf 'secrets\n' >>"$calls"; }
    eve_map_stage_web_artifact() { STAGED_WEB_RELEASE="$TEST_ROOT/release"; printf 'stage\n' >>"$calls"; }
    eve_map_switch_web_release() { printf 'switch\n' >>"$calls"; }
    installer_deploy() { printf 'deploy\n' >>"$calls"; }
    installer_bootstrap_admin() { FIRST_ADMIN_INVITE=one-time-fixture; printf 'bootstrap\n' >>"$calls"; }
    installer_install_backup_timer() { printf 'timer\n' >>"$calls"; }
    installer_write_state() { printf 'state\n' >>"$calls"; }
    local output
    output="$(installer_main --manifest-url file:///fixture-manifest.json)" || return 1
    [[ "$(printf '%s' "$output" | grep -Fc 'one-time-fixture')" == "1" ]] || return 1
    [[ "$(tail -n 1 "$calls")" == "state" ]] || return 1
    grep -Fxq deploy "$calls"
)

test_update_staging() {
    make_web_artifact
    rm -rf -- "$EVE_MAP_HOME/web"
    eve_map_stage_web_artifact "$WEB_ARTIFACT" "$WEB_ARTIFACT_SHA" 1.7.0 || return 1
    eve_map_switch_web_release "$STAGED_WEB_RELEASE" || return 1
    [[ -f "$EVE_MAP_HOME/web/current/index.html" ]] || return 1
    [[ "$(jq -r '.packVersion' "$EVE_MAP_HOME/web/current/data/manifest.json")" == "3466501-fixture-a" ]]
}

test_web_pack_update() {
    local replacement="$TEST_ROOT/replacement-pack"
    local active_release
    make_web_pack "$replacement" "3466501-fixture-b"
    eve_map_install_web_pack "$replacement" || return 1
    active_release="$EVE_MAP_HOME/web/$(eve_map_current_web_release)"
    [[ -f "$active_release/data/web-pack-3466501-fixture-b.json.gz" ]] || return 1
    [[ "$(jq -r '.packVersion' "$active_release/data/manifest.json")" == "3466501-fixture-b" ]]
}

test_diagnostics_redaction() {
    local output
    output="$(printf '%s\n' \
        'Authorization: Bearer secret-token' \
        'invite=esm_inv_abcdefghijklmnopqrstuvwxyz' \
        'device=esm_dev_abcdefghijklmnopqrstuvwxyz' \
        'token_pepper=very-secret' |
        eve_map_redact)"
    [[ "$output" != *secret-token* && "$output" != *esm_inv_* && "$output" != *esm_dev_* && "$output" != *very-secret* ]] || return 1
    [[ "$(printf '%s' "$output" | grep -o '<redacted>' | wc -l)" -ge 4 ]]
}

run_test 'unsupported OS' test_unsupported_os
run_test 'missing root' test_missing_root
run_test 'existing install' test_existing_install
run_test 'DNS mismatch' test_dns_mismatch
run_test 'environment generation' test_env_generation
run_test 'secret generation' test_secret_generation
run_test 'valid release manifest' test_valid_release_manifest
run_test 'invalid release manifest' test_invalid_release_manifest
run_test 'checksum mismatch' test_checksum_mismatch
run_test 'missing Web artifact' test_missing_web_artifact
run_test 'Compose config contract' test_config_contract
run_test 'install success simulation' test_install_success_simulation
run_test 'update staging' test_update_staging
run_test 'Web Pack update' test_web_pack_update
run_test 'diagnostics redaction' test_diagnostics_redaction

printf '\n%d passed, %d failed\n' "$passes" "$failures"
(( failures == 0 ))
