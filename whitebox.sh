#!/usr/bin/env bash
# White-box unit tests of the PL/SQL (packages + triggers) with utPLSQL v3.
#   ./whitebox.sh [name]            run against stack <name> (default "dev"; start it with ./stack.sh up <name>)
#   KNOWN_BUGS=1 ./whitebox.sh ...  also run the known_bug suite (expected to fail until the bugs are fixed)
# Idempotent: installs utPLSQL into the DB if missing, reinstalls the test packages from db/tests,
# resets the demo data, runs every suite. Exit code != 0 when a test fails.
# JUnit XML report: test-results/whitebox/junit.xml
set -euo pipefail
cd "$(dirname "$0")"

ENGINE=${ENGINE:-podman}
NAME=${1:-dev}
DB=ofc-db-$NAME
UT_VERSION=3.2.3      # framework, installed into the DB
CLI_VERSION=3.2.2     # runner, runs on the host (Java)
CACHE=${XDG_CACHE_HOME:-$HOME/.cache}/utplsql
REPORT_DIR=test-results/whitebox

say() { printf '\033[1m== %s\033[0m\n' "$*" >&2; }

# Downloads $1 (GitHub release asset URL) into $2 unless already there, checking its .sha256.
fetch() {
  local url=$1 dest=$2
  [ -s "$dest" ] && return 0
  mkdir -p "$(dirname "$dest")"
  curl -fsSL "$url" -o "$dest.part"
  local expected actual
  expected=$(curl -fsSL "$url.sha256" | grep -oE '[0-9a-f]{64}' | head -1)
  actual=$(shasum -a 256 "$dest.part" | cut -d' ' -f1)
  [ "$expected" = "$actual" ] || { echo "Checksum mismatch for $url" >&2; rm -f "$dest.part"; exit 1; }
  mv "$dest.part" "$dest"
}

download_utplsql() {
  local fw=$CACHE/utPLSQL-$UT_VERSION cli=$CACHE/utPLSQL-cli-$CLI_VERSION
  if [ ! -f "$fw/source/install_headless.sql" ]; then
    say "Downloading utPLSQL $UT_VERSION into $CACHE"
    fetch "https://github.com/utPLSQL/utPLSQL/releases/download/v.$UT_VERSION/utPLSQL.tar.gz" "$CACHE/dl/utPLSQL-$UT_VERSION.tar.gz"
    rm -rf "$fw.tmp" && mkdir -p "$fw.tmp"
    tar -xzf "$CACHE/dl/utPLSQL-$UT_VERSION.tar.gz" -C "$fw.tmp"
    rm -rf "$fw" && mv "$fw.tmp/utPLSQL" "$fw" && rm -rf "$fw.tmp"
  fi
  if [ ! -x "$cli/bin/utplsql" ]; then
    say "Downloading utPLSQL-cli $CLI_VERSION into $CACHE"
    fetch "https://github.com/utPLSQL/utPLSQL-cli/releases/download/v$CLI_VERSION/utPLSQL-cli.zip" "$CACHE/dl/utPLSQL-cli-$CLI_VERSION.zip"
    rm -rf "$cli.tmp" && mkdir -p "$cli.tmp"
    unzip -q "$CACHE/dl/utPLSQL-cli-$CLI_VERSION.zip" -d "$cli.tmp"
    rm -rf "$cli" && mv "$cli.tmp/utPLSQL-cli" "$cli" && rm -rf "$cli.tmp"
    chmod +x "$cli/bin/utplsql"
  fi
}

app_sql() { $ENGINE exec -i "$DB" sqlplus -s -L app/app@FREEPDB1 "$@"; }

utplsql_installed() {
  [ "$(echo "select 'n=' || count(*) from all_objects where owner = 'UT3' and object_name = 'UT'
              and object_type = 'PACKAGE' and status = 'VALID';" | app_sql | grep -c 'n=[1-9]')" -gt 0 ]
}

install_utplsql() {
  say "Installing utPLSQL $UT_VERSION into $DB (as SYS, schema UT3)"
  $ENGINE exec -u 0 "$DB" rm -rf /tmp/utplsql
  $ENGINE cp "$CACHE/utPLSQL-$UT_VERSION/source" "$DB:/tmp/utplsql"
  $ENGINE exec -u 0 "$DB" chmod -R a+rwX /tmp/utplsql
  $ENGINE exec -w /tmp/utplsql "$DB" \
    sqlplus -s -L "sys/oracle@//localhost:1521/FREEPDB1 as sysdba" @install_headless.sql > /dev/null \
    || { $ENGINE exec "$DB" tail -40 /tmp/utplsql/install.log >&2; echo "utPLSQL install failed" >&2; exit 1; }
  utplsql_installed || { echo "utPLSQL is still not installed" >&2; exit 1; }
}

running=$($ENGINE inspect -f '{{.State.Running}}' "$DB" 2>/dev/null || true)
[ "$running" = "true" ] || { echo "Stack '$NAME' is not running: ./stack.sh up $NAME" >&2; exit 1; }
DB_PORT=$($ENGINE port "$DB" 1521 | head -1 | sed 's/.*://')

download_utplsql
utplsql_installed || install_utplsql

say "Installing test packages from db/tests"
# db/ is mounted read-only into the container at /opt/ofc-db (see stack.sh)
$ENGINE exec -w /opt/ofc-db/tests "$DB" sqlplus -s -L app/app@FREEPDB1 @install_tests.sql >&2

say "Resetting demo data"
$ENGINE exec -w /opt/ofc-db "$DB" sqlplus -s -L app/app@FREEPDB1 @seed.sql >&2

TAGS='!known_bug'
[ "${KNOWN_BUGS:-0}" = "1" ] && TAGS='known_bug'
say "Running utPLSQL suites (tags: $TAGS)"
mkdir -p "$REPORT_DIR"
color=(); [ -t 1 ] && color=(-c)
set +e
"$CACHE/utPLSQL-cli-$CLI_VERSION/bin/utplsql" run "app/app@//127.0.0.1:$DB_PORT/FREEPDB1" \
  -p=app --tags="$TAGS" ${color[@]+"${color[@]}"} \
  -f=ut_documentation_reporter \
  -f=ut_junit_reporter -o="$REPORT_DIR/junit.xml"
status=$?
set -e
echo "JUnit report: $REPORT_DIR/junit.xml" >&2
[ "$TAGS" = '!known_bug' ] && echo "Skipped the known_bug suite; KNOWN_BUGS=1 $0 $NAME shows those failing." >&2
exit $status
