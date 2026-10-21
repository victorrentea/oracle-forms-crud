#!/usr/bin/env bash
# Opens the traces of the last test run in the Playwright Trace Viewer: steps on the left,
# the Forms window at every step in the middle, the SQL / package calls under "Network".
# Serves Playwright's own static viewer (from ../../ui-tests/node_modules) next to the trace zips.
set -euo pipefail
cd "$(dirname "$0")/target/forms-traces"
viewer=$(cd ../../../../ui-tests && pwd)/node_modules/playwright-core/lib/vite/traceViewer
[ -d "$viewer" ] || (cd ../../../../ui-tests && npm ci --silent)
ln -sfn "$viewer" traceviewer
port=${PORT:-9323}
python3 -m http.server "$port" --bind 127.0.0.1 >/dev/null 2>&1 &
echo "Serving traces on http://127.0.0.1:$port (pid $!, stop with: kill $!)"
sleep 1
open "http://127.0.0.1:$port/index.html"
