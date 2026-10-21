#!/usr/bin/env bash
# Opens the real Oracle Forms "Orders" screen on this Mac, outside any browser, through the
# Forms Standalone Launcher, with the forms-driver agent inside the client JVM (HTTP on :9971).
#   ./run-client.sh            (needs the Forms server from forms/ running on localhost:9001)
set -euo pipefail
cd "$(dirname "$0")"
JAVA8=${JAVA8:-/Library/Java/JavaVirtualMachines/jdk1.8.0_461.jdk/Contents/Home}
SERVER=${SERVER:-http://127.0.0.1:9001}
mkdir -p target
[ -f target/frmsal.jar ] || curl -sf -o target/frmsal.jar "$SERVER/forms/java/frmsal.jar"
[ -f driver/target/forms-driver.jar ] || driver/build.sh >/dev/null
exec "$JAVA8/bin/java" -javaagent:driver/target/forms-driver.jar=${DRIVER_PORT:-9971} \
  -jar target/frmsal.jar -url "$SERVER/forms/frmservlet?config=orders"
