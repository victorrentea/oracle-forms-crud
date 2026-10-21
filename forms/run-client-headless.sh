#!/usr/bin/env bash
# Like run-client.sh, but the Forms client runs inside the Lima VM "forms" on a virtual X display
# (Xvfb): nothing appears on this Mac's screen. Lima forwards the driver port back to localhost.
# The GNOME accessibility bridge Ubuntu's JDK loads by default deadlocks the Forms client: off.
#   FORMS_CONFIG=orders2 DRIVER_PORT=9973 ./run-client-headless.sh     (one per parallel worker)
set -euo pipefail
cd "$(dirname "$0")"
CONFIG=${FORMS_CONFIG:-orders}
PORT=${DRIVER_PORT:-9971}
DIR=/tmp/forms-client-$PORT
mkdir -p target
[ -f target/frmsal.jar ] || curl -sf -o target/frmsal.jar http://127.0.0.1:9001/forms/java/frmsal.jar
[ -f driver/target/forms-driver.jar ] || driver/build.sh >/dev/null
limactl shell forms mkdir -p "$DIR"
limactl copy target/frmsal.jar driver/target/forms-driver.jar "forms:$DIR/"
exec limactl shell forms bash -c "cd $DIR && exec xvfb-run -a -s '-screen 0 1280x800x24' \
  /usr/lib/jvm/java-8-openjdk-arm64/bin/java -Djavax.accessibility.assistive_technologies=' ' \
  -javaagent:forms-driver.jar=$PORT -jar frmsal.jar -url 'http://127.0.0.1:9001/forms/frmservlet?config=$CONFIG'"
