#!/usr/bin/env bash
# Runs isolated copies of the whole system (Oracle Free + the app), each on host-chosen ports.
#   ./stack.sh up [name]        start (or refresh) stack <name>, default "dev"; prints its URLs
#   ./stack.sh env [name]       print BASE_URL / DB_URL, for: eval "$(./stack.sh env)"
#   ./stack.sh seed [name]      reset the data to the known starting point
#   ./stack.sh reinstall [name] re-run db/install.sql (schema + PL/SQL) and seed
#   ./stack.sh redeploy [name]  rebuild and restart only the app
#   ./stack.sh sql [name]       sqlplus prompt as the app user
#   ./stack.sh down [name] | ls
# ENGINE=docker ./stack.sh ... works too; podman is the default.
set -euo pipefail
cd "$(dirname "$0")"

ENGINE=${ENGINE:-podman}
DB_IMAGE=docker.io/gvenzl/oracle-free:23-slim-faststart
APP_IMAGE=localhost/orders-form:latest
CMD=${1:-help}
NAME=${2:-dev}
NET=ofc-$NAME
DB=ofc-db-$NAME
APP=ofc-app-$NAME

running() { [ "$($ENGINE inspect -f '{{.State.Running}}' "$1" 2>/dev/null)" = "true" ]; }
host_port() { $ENGINE port "$1" "$2" | head -1 | sed 's/.*://'; }
# grep -q would close the pipe early and trip pipefail, so count instead
db_ready() { [ "$($ENGINE logs "$DB" 2>&1 | grep -c 'DATABASE IS READY TO USE')" -gt 0 ]; }
sqlplus_run() { $ENGINE exec -w /opt/ofc-db "$DB" sqlplus -s app/app@FREEPDB1 "$@"; }

start_db() {
  $ENGINE network exists "$NET" 2>/dev/null || $ENGINE network create "$NET" >/dev/null
  if ! running "$DB"; then
    $ENGINE rm -f "$DB" >/dev/null 2>&1 || true
    $ENGINE run -d --name "$DB" --network "$NET" --network-alias db -p 127.0.0.1::1521 \
      -e ORACLE_PASSWORD=oracle -e APP_USER=app -e APP_USER_PASSWORD=app \
      -v "$PWD/db:/opt/ofc-db:ro" "$DB_IMAGE" >/dev/null
    echo "Waiting for Oracle in $DB..." >&2
    until db_ready; do
      running "$DB" || { $ENGINE logs "$DB" | tail -30 >&2; echo "Oracle did not start" >&2; exit 1; }
      sleep 2
    done
  fi
  schema_installed || install_db
}

schema_installed() {
  [ "$(echo "select 'n=' || count(*) from user_objects where object_name = 'ORDERS_API';" \
        | $ENGINE exec -i "$DB" sqlplus -s app/app@FREEPDB1 | grep -c 'n=[1-9]')" -gt 0 ]
}

install_db() {
  sqlplus_run @install.sql >&2
  sqlplus_run @seed.sql >&2
}

start_app() {
  (cd app && mvn -q package -DskipTests)
  $ENGINE build -q -t "$APP_IMAGE" app >/dev/null
  $ENGINE rm -f "$APP" >/dev/null 2>&1 || true
  $ENGINE run -d --name "$APP" --network "$NET" -p 127.0.0.1::8080 \
    -e DB_URL=jdbc:oracle:thin:@db:1521/FREEPDB1 "$APP_IMAGE" >/dev/null
  local url="http://127.0.0.1:$(host_port "$APP" 8080)"
  echo "Waiting for the app at $url..." >&2
  for _ in $(seq 1 60); do
    curl -sf -o /dev/null "$url/orders" && return 0
    sleep 1
  done
  $ENGINE logs "$APP" | tail -30 >&2
  echo "App did not start" >&2
  exit 1
}

print_env() {
  echo "export BASE_URL=http://127.0.0.1:$(host_port "$APP" 8080)"
  echo "export DB_URL=jdbc:oracle:thin:@127.0.0.1:$(host_port "$DB" 1521)/FREEPDB1"
}

case "$CMD" in
  up)        start_db; start_app; print_env ;;
  env)       print_env ;;
  seed)      sqlplus_run @seed.sql ;;
  reinstall) install_db ;;
  redeploy)  start_app; print_env ;;
  sql)       $ENGINE exec -it "$DB" sqlplus app/app@FREEPDB1 ;;
  down)      $ENGINE rm -f "$APP" "$DB" >/dev/null 2>&1 || true
             $ENGINE network rm "$NET" >/dev/null 2>&1 || true ;;
  ls)        $ENGINE ps --filter name=ofc- --format '{{.Names}}\t{{.Status}}\t{{.Ports}}' ;;
  *)         sed -n '2,11p' "$0" ;;
esac
