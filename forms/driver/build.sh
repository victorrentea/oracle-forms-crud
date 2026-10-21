#!/usr/bin/env bash
# Builds forms-driver.jar (Java 8 bytecode, it runs inside the Forms client JVM).
set -euo pipefail
cd "$(dirname "$0")"
JAVA8=${JAVA8:-/Library/Java/JavaVirtualMachines/jdk1.8.0_461.jdk/Contents/Home}
rm -rf target && mkdir -p target/classes
"$JAVA8/bin/javac" -d target/classes $(find src -name '*.java')
printf 'Premain-Class: ro.victorrentea.formsdriver.Agent\n' > target/MANIFEST.MF
"$JAVA8/bin/jar" cfm target/forms-driver.jar target/MANIFEST.MF -C target/classes .
echo target/forms-driver.jar
