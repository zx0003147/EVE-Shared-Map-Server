#!/usr/bin/env bash
set -Eeuo pipefail

TEST_ROOT="$(mktemp -d)"
REPOSITORY_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"
CLI_HOME="$TEST_ROOT/custom-home"
INSTALLED_ROOT="$TEST_ROOT/installed"
SYMLINK_PATH="$TEST_ROOT/bin/eve-map"
MOCK_BIN="$TEST_ROOT/mock-bin"
trap 'rm -rf -- "$TEST_ROOT"' EXIT HUP INT TERM

mkdir -p "$CLI_HOME/web/current/data" "$INSTALLED_ROOT" "$(dirname -- "$SYMLINK_PATH")" "$MOCK_BIN"
cp -a -- "$REPOSITORY_ROOT/ops" "$INSTALLED_ROOT/ops"
ln -s "$INSTALLED_ROOT/ops/eve-map" "$SYMLINK_PATH"
printf 'SHARED_MAP_DOMAIN=markers.example.test\nSHARED_MAP_WEB_DOMAIN=map.example.test\nSHARED_MAP_EXPECTED_FLYWAY_VERSION=4\n' \
    >"$CLI_HOME/.env.production"
printf 'fixture\n' >"$CLI_HOME/docker-compose.prod.yml"
printf 'selfHostedVersion=1.1.1\nwebVersion=1.9.1\nserverVersion=0.3.1\ninstallerVersion=1\n' \
    >"$CLI_HOME/.installer-state"
printf '{"schemaVersion":1,"sdeBuild":3466501,"packVersion":"fixture"}\n' \
    >"$CLI_HOME/web/current/data/manifest.json"

cat >"$MOCK_BIN/id" <<'EOF'
#!/usr/bin/env bash
[[ "${1:-}" == "-u" ]] && printf '0\n'
EOF
cat >"$MOCK_BIN/docker" <<'EOF'
#!/usr/bin/env bash
if [[ "${1:-}" == "inspect" ]]; then
    printf 'healthy\n'
elif [[ "$*" == *" ps -q "* ]]; then
    printf 'fixture-container\n'
elif [[ "$*" == *" exec -T postgres "* ]]; then
    printf '4\n'
fi
EOF
cat >"$MOCK_BIN/curl" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
cat >"$MOCK_BIN/jq" <<'EOF'
#!/usr/bin/env bash
query="${2:-}"
file="${3:-${2:-}}"
case "$query" in
    '.schemaVersion == 1') grep -q '"schemaVersion":1' "$file" ;;
    '.sdeBuild') sed -n 's/.*"sdeBuild":\([0-9]*\).*/\1/p' "$file" ;;
    '.packVersion') sed -n 's/.*"packVersion":"\([^"]*\)".*/\1/p' "$file" ;;
    *) exit 2 ;;
esac
EOF
chmod +x "$MOCK_BIN/id" "$MOCK_BIN/docker" "$MOCK_BIN/curl" "$MOCK_BIN/jq"

invoke() {
    local executable="$1"
    local command="$2"
    EVE_MAP_HOME="$CLI_HOME" PATH="$MOCK_BIN:$PATH" DOCKER_BIN="$MOCK_BIN/docker" \
        "$executable" "$command"
}

for command in help status version; do
    source_output="$(invoke "$REPOSITORY_ROOT/ops/eve-map" "$command")"
    installed_output="$(invoke "$INSTALLED_ROOT/ops/eve-map" "$command")"
    symlink_output="$(invoke "$SYMLINK_PATH" "$command")"
    [[ "$source_output" == "$installed_output" ]]
    [[ "$installed_output" == "$symlink_output" ]]
done

[[ "$symlink_output" == *"selfHostedVersion=1.1.1"* ]]
[[ "$symlink_output" == *"serverVersion=0.3.1"* ]]
[[ "$symlink_output" == *"webPack=SDE 3466501 / fixture"* ]]
