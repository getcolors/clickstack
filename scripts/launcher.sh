#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
launcher="$root/skills/package-clickstack-green/green"
grep -q 'io.github.getcolors.clickstack.workflow/workflow' "$launcher"
grep -q 'def \^:private clickstack-sha' "$launcher"
[[ -L "$root/green" ]] && [[ $(readlink "$root/green") == skills/package-clickstack-green/green ]]
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
cp "$launcher" "$tmp/green"; chmod +x "$tmp/green"
sed "s#WORKDIR#.colors#" "$root/test/fixtures/colors.yml" > "$tmp/colors.yml"
(cd "$tmp" && CLICKSTACK_LIB_ROOT="$root" ./green build >/dev/null)
[[ -f "$tmp/.colors/clickstack-fixture/clickstack-infrastructure/main.tf" ]]
[[ -f "$tmp/.colors/clickstack-fixture/clickstack-ansible/compose.yml" ]]
# The launcher walks up for colors.yml, so any subdirectory works.
mkdir -p "$tmp/nested/path"
(cd "$tmp/nested/path" && CLICKSTACK_LIB_ROOT="$root" ../../green build >/dev/null)
# The profile guard is the whole reason COLORS_PAR_PROFILE is refused: an
# overlay would point one deployment at another's state.
out=$(cd "$tmp" && CLICKSTACK_LIB_ROOT="$root" COLORS_PAR_PROFILE=wrong ./green build 2>&1 || true)
grep -q COLORS_PAR_PROFILE <<<"$out"
[[ ! -d "$tmp/.colors/wrong" ]]
echo 'launcher: all checks passed'
