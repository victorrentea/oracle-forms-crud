#!/usr/bin/env bash
# Runs INSIDE the x86 Oracle Linux 8 container, as root. Installers are mounted read-only at /install:
#   jdk-8u511-linux-x64.tar.gz, fmw_12.2.1.4.0_infrastructure_Disk1_1of1.zip, V1033708-01.zip (Forms 12.2.1.19)
# Result: Oracle JDK 8 in /u01/jdk8, FMW Infrastructure + Forms & Reports in /u01/oracle/middleware.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
export JAVA_HOME=/u01/jdk8
ORACLE_HOME=/u01/oracle/middleware
WORK=/u01/tmp

id oracle >/dev/null 2>&1 || { groupadd -g 1000 oinstall; useradd -u 1000 -g oinstall -m oracle; }
mkdir -p /u01/oracle "$WORK"
chown -R oracle:oinstall /u01
echo -e "inventory_loc=/u01/oraInventory\ninst_group=oinstall" > /u01/oraInst.loc
as_oracle() { su oracle -c "$*"; }

if [ ! -x $JAVA_HOME/bin/java ]; then
  as_oracle "mkdir -p $JAVA_HOME && tar -xzf /install/jdk-8u511-linux-x64.tar.gz -C $JAVA_HOME --strip-components=1"
fi

if [ ! -d $ORACLE_HOME/oracle_common ]; then
  as_oracle "cd $WORK && unzip -oq /install/fmw_12.2.1.4.0_infrastructure_Disk1_1of1.zip"
  as_oracle "$JAVA_HOME/bin/java -Xmx1g -jar $WORK/fmw_12.2.1.4.0_infrastructure.jar -silent \
     -responseFile $HERE/infra.rsp -invPtrLoc /u01/oraInst.loc -ignoreSysPrereqs"
  rm -f $WORK/fmw_12.2.1.4.0_infrastructure.jar
fi

if [ ! -d $ORACLE_HOME/forms ]; then
  as_oracle "cd $WORK && unzip -oq /install/V1033708-01.zip"
  bin=$(ls $WORK/fmw_12.2.1.19.0_fr_linux64*.bin | head -1)
  chmod +x "$bin"
  as_oracle "$bin -silent -responseFile $HERE/forms.rsp -invPtrLoc /u01/oraInst.loc -jreLoc $JAVA_HOME -ignoreSysPrereqs"
  rm -f $WORK/fmw_12.2.1.19.0_fr_linux64*
fi
echo "Oracle home ready: $(du -sh $ORACLE_HOME | cut -f1)"
