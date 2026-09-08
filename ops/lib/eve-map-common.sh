#!/usr/bin/env bash
# Uppercase values are deliberate outputs consumed by installer/CLI callers.
# shellcheck disable=SC2034

EVE_MAP_INSTALLER_VERSION="1"

eve_map_die() {
    printf 'eve-map: %s\n' "$*" >&2
    exit 1
}

eve_map_require_command() {
    command -v "$1" >/dev/null 2>&1 || eve_map_die "required command is unavailable: $1"
}

eve_map_is_safe_version() {
    [[ "$1" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]]
}

eve_map_is_sha256() {
    [[ "$1" =~ ^[0-9a-f]{64}$ ]]
}

eve_map_is_pinned_image() {
    local image="$1"
    [[ "$image" =~ ^[A-Za-z0-9._/@:-]+$ ]] || return 1
    if [[ "$image" =~ @sha256:[0-9a-f]{64}$ ]]; then
        return 0
    fi
    [[ "$image" =~ :[^/:]+$ ]] || return 1
    [[ "$image" != *:latest ]]
}

eve_map_is_ipv4() {
    local address="$1"
    local -a octets
    local octet
    [[ "$address" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]] || return 1
    IFS='.' read -r -a octets <<<"$address"
    for octet in "${octets[@]}"; do
        (( 10#$octet <= 255 )) || return 1
    done
}

eve_map_validate_hostname() {
    local hostname="${1,,}"
    local -a labels
    local label
    [[ ${#hostname} -le 253 ]] || return 1
    [[ "$hostname" =~ ^[a-z0-9]([a-z0-9.-]*[a-z0-9])?$ ]] || return 1
    [[ "$hostname" == *.* ]] || return 1
    [[ "$hostname" != *..* ]] || return 1
    IFS='.' read -r -a labels <<<"$hostname"
    for label in "${labels[@]}"; do
        [[ ${#label} -le 63 ]] || return 1
        [[ "$label" =~ ^[a-z0-9]([a-z0-9-]*[a-z0-9])?$ ]] || return 1
    done
}

eve_map_sha256() {
    sha256sum "$1" | awk '{print $1}'
}

eve_map_read_env() {
    local key="$1"
    local file="$2"
    awk -F= -v wanted="$key" '$1 == wanted { value = substr($0, index($0, "=") + 1) } END { print value }' "$file" |
        tr -d '\r'
}

eve_map_set_env() {
    local key="$1"
    local value="$2"
    local file="$3"
    local temporary="$file.new.$$"
    [[ "$key" =~ ^[A-Z0-9_]+$ ]] || eve_map_die "unsafe environment key"
    [[ "$value" != *$'\n'* && "$value" != *$'\r'* ]] || eve_map_die "unsafe environment value"
    awk -F= -v wanted="$key" -v replacement="$value" '
        BEGIN { replaced = 0 }
        $1 == wanted { print wanted "=" replacement; replaced = 1; next }
        { print }
        END { if (!replaced) print wanted "=" replacement }
    ' "$file" >"$temporary"
    chmod --reference="$file" "$temporary"
    chown --reference="$file" "$temporary"
    mv -f -- "$temporary" "$file"
}

eve_map_release_manifest_load() {
    local manifest="$1"
    eve_map_require_command jq
    [[ -r "$manifest" && -s "$manifest" ]] || eve_map_die "release manifest is missing or empty"
    jq -e '
        type == "object" and
        .schemaVersion == 1 and
        (.selfHostedVersion | type == "string" and length > 0) and
        (.webVersion | type == "string" and length > 0) and
        (.serverVersion | type == "string" and length > 0) and
        (.webArtifactUrl | type == "string" and length > 0) and
        (.webSha256 | type == "string" and test("^[0-9a-f]{64}$")) and
        (.serverImage | type == "string" and length > 0) and
        (.opsImage | type == "string" and length > 0) and
        (.flywayVersion | type == "number" and floor == . and . > 0) and
        (.minimumInstallerVersion | type == "number" and floor == . and . > 0)
    ' "$manifest" >/dev/null || eve_map_die "release manifest has an invalid schema"

    RELEASE_SELF_HOSTED_VERSION="$(jq -er '.selfHostedVersion' "$manifest")"
    RELEASE_WEB_VERSION="$(jq -er '.webVersion' "$manifest")"
    RELEASE_SERVER_VERSION="$(jq -er '.serverVersion' "$manifest")"
    RELEASE_WEB_ARTIFACT_URL="$(jq -er '.webArtifactUrl' "$manifest")"
    RELEASE_WEB_SHA256="$(jq -er '.webSha256' "$manifest")"
    RELEASE_SERVER_IMAGE="$(jq -er '.serverImage' "$manifest")"
    RELEASE_OPS_IMAGE="$(jq -er '.opsImage' "$manifest")"
    RELEASE_FLYWAY_VERSION="$(jq -er '.flywayVersion | tostring' "$manifest")"
    RELEASE_MINIMUM_INSTALLER_VERSION="$(jq -er '.minimumInstallerVersion | tostring' "$manifest")"

    eve_map_is_safe_version "$RELEASE_SELF_HOSTED_VERSION" || eve_map_die "selfHostedVersion is unsafe"
    eve_map_is_safe_version "$RELEASE_WEB_VERSION" || eve_map_die "webVersion is unsafe"
    eve_map_is_safe_version "$RELEASE_SERVER_VERSION" || eve_map_die "serverVersion is unsafe"
    eve_map_is_sha256 "$RELEASE_WEB_SHA256" || eve_map_die "webSha256 is invalid"
    eve_map_is_pinned_image "$RELEASE_SERVER_IMAGE" || eve_map_die "serverImage must use an explicit non-latest tag or digest"
    eve_map_is_pinned_image "$RELEASE_OPS_IMAGE" || eve_map_die "opsImage must use an explicit non-latest tag or digest"
    (( RELEASE_MINIMUM_INSTALLER_VERSION <= EVE_MAP_INSTALLER_VERSION )) ||
        eve_map_die "release requires installer version $RELEASE_MINIMUM_INSTALLER_VERSION or newer"
    case "$RELEASE_WEB_ARTIFACT_URL" in
        https://*) [[ "$RELEASE_WEB_ARTIFACT_URL" != *[[:space:]]* ]] || eve_map_die "Web artifact URL contains whitespace" ;;
        file://*) [[ "${EVE_MAP_TEST_MODE:-0}" == "1" ]] || eve_map_die "Web artifact URL must use HTTPS" ;;
        *) eve_map_die "Web artifact URL must use HTTPS" ;;
    esac
}

eve_map_download() {
    local url="$1"
    local destination="$2"
    if [[ "$url" == file://* ]]; then
        [[ "${EVE_MAP_TEST_MODE:-0}" == "1" ]] || eve_map_die "file URLs are test-only"
        cp -- "${url#file://}" "$destination"
        return
    fi
    curl --fail --silent --show-error --location --proto '=https' --tlsv1.2 \
        --connect-timeout 15 --retry 3 --retry-delay 2 "$url" --output "$destination"
}

eve_map_validate_web_pack_directory() {
    local data_directory="$1"
    local selected_file="${2:-}"
    local manifest="$data_directory/manifest.json"
    [[ -f "$manifest" && -s "$manifest" && ! -L "$manifest" ]] || eve_map_die "Web Pack manifest.json is missing"
    jq -e '
        type == "object" and
        .schemaVersion == 1 and
        (.packVersion | type == "string" and test("^[A-Za-z0-9._-]+$")) and
        (.fileName == ("web-pack-" + .packVersion + ".json.gz")) and
        (.sizeBytes | type == "number" and floor == . and . > 0) and
        (.sha256 | type == "string" and test("^[0-9a-f]{64}$")) and
        (.sdeBuild | type == "number" and floor == . and . > 0)
    ' "$manifest" >/dev/null || eve_map_die "Web Pack manifest is invalid or unsupported"

    WEB_PACK_FILE_NAME="$(jq -er '.fileName' "$manifest")"
    WEB_PACK_VERSION="$(jq -er '.packVersion' "$manifest")"
    WEB_PACK_SCHEMA_VERSION="$(jq -er '.schemaVersion | tostring' "$manifest")"
    WEB_PACK_SDE_BUILD="$(jq -er '.sdeBuild | tostring' "$manifest")"
    local expected_size expected_sha256 pack
    expected_size="$(jq -er '.sizeBytes | tostring' "$manifest")"
    expected_sha256="$(jq -er '.sha256' "$manifest")"
    pack="$data_directory/$WEB_PACK_FILE_NAME"
    [[ -f "$pack" && -s "$pack" && ! -L "$pack" ]] || eve_map_die "versioned Web Pack is missing"
    if [[ -n "$selected_file" && "$(basename -- "$selected_file")" != "$WEB_PACK_FILE_NAME" ]]; then
        eve_map_die "selected Web Pack file is not referenced by manifest.json"
    fi
    [[ "$(stat -c '%s' "$pack")" == "$expected_size" ]] || eve_map_die "Web Pack size does not match manifest.json"
    [[ "$(eve_map_sha256 "$pack")" == "$expected_sha256" ]] || eve_map_die "Web Pack checksum does not match manifest.json"
    gzip -t "$pack" || eve_map_die "Web Pack gzip stream is invalid"
}

eve_map_validate_web_directory() {
    local site="$1"
    local expected_web_version="$2"
    local relative
    while IFS= read -r relative; do
        eve_map_die "Web artifact contains a symbolic link: $relative"
    done < <(find "$site" -type l -print -quit)

    for relative in \
        index.html \
        web-client.js \
        web-client.css \
        web-pack-loader.mjs \
        pwa-runtime.mjs \
        manifest.webmanifest \
        service-worker.js \
        icons/app-icon-192.png \
        icons/app-icon-512.png \
        data/manifest.json \
        self-hosted-web.json; do
        [[ -f "$site/$relative" && -s "$site/$relative" ]] || eve_map_die "Web artifact is missing required file: $relative"
    done
    jq -e --arg version "$expected_web_version" '
        type == "object" and
        .formatVersion == 1 and
        .artifactType == "eve-map-self-hosted-web" and
        .webVersion == $version and
        .webPackSchemaVersion == 1 and
        (.webPackVersion | type == "string" and test("^[A-Za-z0-9._-]+$")) and
        (.sdeBuild | type == "number" and floor == . and . > 0)
    ' "$site/self-hosted-web.json" >/dev/null || eve_map_die "self-hosted Web metadata is invalid or has the wrong version"
    eve_map_validate_web_pack_directory "$site/data"
    [[ "$(jq -er '.webPackVersion' "$site/self-hosted-web.json")" == "$WEB_PACK_VERSION" ]] ||
        eve_map_die "self-hosted metadata and Web Pack versions differ"
    [[ "$(jq -er '.sdeBuild | tostring' "$site/self-hosted-web.json")" == "$WEB_PACK_SDE_BUILD" ]] ||
        eve_map_die "self-hosted metadata and Web Pack SDE builds differ"
}

eve_map_stage_web_artifact() {
    local artifact="$1"
    local expected_sha256="$2"
    local expected_web_version="$3"
    local web_root="$EVE_MAP_HOME/web"
    local stage release_id destination
    [[ "$(eve_map_sha256 "$artifact")" == "$expected_sha256" ]] || eve_map_die "Web artifact checksum mismatch"
    if [[ "${EVE_MAP_TEST_MODE:-0}" == "1" ]]; then
        mkdir -p "$web_root/releases"
    else
        install -d -m 0750 "$web_root" "$web_root/releases"
    fi
    stage="$(mktemp -d "$web_root/.staging.XXXXXXXX")"
    if unzip -Z1 "$artifact" | grep -E '(^/|(^|/)\.\.(/|$)|\\)' >/dev/null; then
        rm -rf -- "$stage"
        eve_map_die "Web artifact contains an unsafe archive path"
    fi
    if ! unzip -q "$artifact" -d "$stage"; then
        rm -rf -- "$stage"
        eve_map_die "Web artifact could not be extracted"
    fi
    if ! eve_map_validate_web_directory "$stage" "$expected_web_version"; then
        rm -rf -- "$stage"
        return 1
    fi
    release_id="$expected_web_version-${expected_sha256:0:12}"
    destination="$web_root/releases/$release_id"
    if [[ -e "$destination" ]]; then
        rm -rf -- "$stage"
        eve_map_validate_web_directory "$destination" "$expected_web_version"
    else
        mv -- "$stage" "$destination"
    fi
    STAGED_WEB_RELEASE="$destination"
}

eve_map_current_web_release() {
    local current="$EVE_MAP_HOME/web/current"
    if [[ "${EVE_MAP_TEST_MODE:-0}" == "1" && -r "$EVE_MAP_HOME/web/.current-test-target" ]]; then
        cat "$EVE_MAP_HOME/web/.current-test-target"
        return
    fi
    [[ -L "$current" ]] || return 0
    readlink "$current"
}

eve_map_switch_web_release() {
    local release="$1"
    local releases_root="$EVE_MAP_HOME/web/releases"
    local canonical_release canonical_root relative temporary
    canonical_release="$(readlink -f "$release")"
    canonical_root="$(readlink -f "$releases_root")"
    [[ "$canonical_release" == "$canonical_root/"* && -d "$canonical_release" ]] ||
        eve_map_die "refusing to activate a Web release outside the managed release root"
    relative="releases/${canonical_release##*/}"
    temporary="$EVE_MAP_HOME/web/.current.$$"
    ln -s "$relative" "$temporary"
    mv -Tf -- "$temporary" "$EVE_MAP_HOME/web/current"
    if [[ "${EVE_MAP_TEST_MODE:-0}" == "1" ]]; then
        printf '%s\n' "$relative" >"$EVE_MAP_HOME/web/.current-test-target"
    fi
}

eve_map_install_web_pack() {
    local input="$1"
    local source_directory selected_file=""
    [[ -e "$input" ]] || eve_map_die "Web Pack input does not exist"
    if [[ -d "$input" ]]; then
        source_directory="$(readlink -f "$input")"
    elif [[ "$(basename -- "$input")" == "manifest.json" ]]; then
        source_directory="$(readlink -f "$(dirname -- "$input")")"
    elif [[ "$input" == *.json.gz ]]; then
        source_directory="$(readlink -f "$(dirname -- "$input")")"
        selected_file="$(readlink -f "$input")"
    else
        eve_map_die "Web Pack input must be a directory, manifest.json, or versioned .json.gz file"
    fi
    eve_map_validate_web_pack_directory "$source_directory" "$selected_file"

    local current_link current_target destination manifest_temp pack_temp
    current_link="$(eve_map_current_web_release)"
    [[ "$current_link" =~ ^releases/[A-Za-z0-9][A-Za-z0-9._-]*$ ]] ||
        eve_map_die "no managed Web release is active"
    current_target="$(readlink -f "$EVE_MAP_HOME/web/$current_link")"
    [[ "$current_target" == "$(readlink -f "$EVE_MAP_HOME/web/releases")/"* && -d "$current_target/data" ]] ||
        eve_map_die "no managed Web release is active"
    destination="$current_target/data"
    pack_temp="$destination/.$WEB_PACK_FILE_NAME.$$"
    manifest_temp="$destination/.manifest.json.$$"
    install -m 0644 "$source_directory/$WEB_PACK_FILE_NAME" "$pack_temp"
    mv -f -- "$pack_temp" "$destination/$WEB_PACK_FILE_NAME"
    install -m 0644 "$source_directory/manifest.json" "$manifest_temp"
    mv -f -- "$manifest_temp" "$destination/manifest.json"
}

eve_map_redact() {
    sed -E \
        -e 's/(Authorization:[[:space:]]*(Bearer[[:space:]]+)?)[^[:space:]]+/\1<redacted>/Ig' \
        -e 's/esm_(inv|dev)_[A-Za-z0-9._-]+/<redacted>/g' \
        -e 's/((db[_-]?password|database[_-]?password|token[_-]?pepper|backup[_-]?passphrase|secret)[[:space:]]*[=:][[:space:]]*)[^[:space:]]+/\1<redacted>/Ig'
}
