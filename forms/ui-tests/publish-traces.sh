#!/usr/bin/env bash
# Publishes the traces of the last run, with Playwright's Trace Viewer, to GitHub Pages
# (branch gh-pages): https://victorrentea.github.io/oracle-forms-crud/
set -euo pipefail
cd "$(dirname "$0")"
site=$(mktemp -d)
mkdir -p "$site/traces"
cp -RL ../../ui-tests/node_modules/playwright-core/lib/vite/traceViewer "$site/traceviewer"
cp target/forms-traces/*.zip "$site/traces/"
sed 's|traceviewer/index.html?trace=../|traceviewer/index.html?trace=../traces/|g' target/forms-traces/index.html > "$site/index.html"
touch "$site/.nojekyll"  # serve files and folders starting with _ too
git -C "$site" init -q -b gh-pages
git -C "$site" add -A
git -C "$site" commit -qm "Traces of the Oracle Forms UI tests, $(date -u +%Y-%m-%dT%H:%MZ)"
git -C "$site" push -q -f "$(git remote get-url origin)" gh-pages
rm -rf "$site"
echo "Published: https://victorrentea.github.io/oracle-forms-crud/ (live in ~1 min)"
