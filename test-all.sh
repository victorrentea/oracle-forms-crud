#!/usr/bin/env bash
# Every test layer, end to end, on a fresh throw-away stack:
#   1. white-box  utPLSQL unit tests of the packages and triggers   (./whitebox.sh)
#   2. black-box  HTTP specs + golden masters + DB record/replay     (blackbox-tests, Maven)
#   3. black-box  the same rules through a headless browser          (ui-tests, Playwright)
#   ./test-all.sh [stack]     default stack name: "all" (removed at the end unless KEEP=1)
set -euo pipefail
cd "$(dirname "$0")"
STACK=${1:-all}
export STACK

[ "${KEEP:-0}" = 1 ] || trap './stack.sh down "$STACK"' EXIT
eval "$(./stack.sh up "$STACK")"

failed=()
echo "== 1. white-box (utPLSQL)" && ./whitebox.sh "$STACK" || failed+=(white-box)
echo "== 2. black-box over HTTP + golden masters" && (cd blackbox-tests && mvn -q test) || failed+=(black-box-http)
echo "== 3. black-box in a headless browser" && (cd ui-tests && { [ -d node_modules ] || npm ci --silent; } && npx playwright test) || failed+=(black-box-ui)

if [ ${#failed[@]} -gt 0 ]; then
  echo "FAILED: ${failed[*]}" >&2
  exit 1
fi
echo "All test layers green."
