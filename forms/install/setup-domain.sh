#!/usr/bin/env bash
# Runs INSIDE the x86 container, as user oracle, after install-oracle-home.sh.
# Creates a Forms 12c WebLogic domain whose repository schemas (RCU) live in the same
# Oracle Free database as the application, then starts AdminServer + WLS_FORMS.
# Adapted from aws-samples/sample-oracleforms-to-angular scripts/forms-setup.sh (MIT-0).
#   setup-domain.sh <phase>...   phases: rcu domain component config start   (or: all)
# Env: DB_HOSTPORT (e.g. db:46371) — where Oracle Free listens, as seen from this container.
set -euo pipefail
export JAVA_HOME=/u01/jdk8
MW_HOME=/u01/oracle/middleware
DOMAIN_HOME=/u01/oracle/domains/forms_domain
DB_HOSTPORT=${DB_HOSTPORT:?set DB_HOSTPORT, e.g. db:46371}
DB_SERVICE=FREEPDB1
SYS_PASSWORD=oracle           # demo-only passwords, local container
RCU_PREFIX=FRM
RCU_PASSWORD=Welcome1
WLS_PASSWORD=welcome1
FMWCONFIG=$DOMAIN_HOME/config/fmwconfig
export CONFIG_JVM_ARGS="-Djava.security.egd=file:/dev/./urandom"

log() { echo -e "\n=== $* ==="; }

phase_rcu() {
  log "RCU schemas ${RCU_PREFIX}_* in $DB_HOSTPORT/$DB_SERVICE"
  printf '%s\n%s\n' "$SYS_PASSWORD" "$RCU_PASSWORD" | \
  "$MW_HOME/oracle_common/bin/rcu" -silent -createRepository \
    -databaseType ORACLE -connectString "$DB_HOSTPORT/$DB_SERVICE" \
    -dbUser SYS -dbRole SYSDBA -schemaPrefix "$RCU_PREFIX" \
    -component STB -component OPSS -component IAU -component IAU_APPEND \
    -component IAU_VIEWER -component WLS -component MDS \
    -useSamePasswordForAllSchemaUsers true
}

phase_domain() {
  log "WebLogic domain $DOMAIN_HOME"
  mkdir -p "$(dirname "$DOMAIN_HOME")"
  cat > /tmp/create_domain.py <<PYEOF
mw = '$MW_HOME'
readTemplate(mw + '/wlserver/common/templates/wls/wls.jar')
addTemplate(mw + '/oracle_common/common/templates/wls/oracle.jrf_template.jar')
addTemplate(mw + '/em/common/templates/wls/oracle.em_wls_template.jar')
addTemplate(mw + '/forms/common/templates/wls/forms_template.jar')
setOption('ServerStartMode', 'dev')
cd('/Security/base_domain/User/weblogic')
cmo.setPassword('$WLS_PASSWORD')
cd('/JDBCSystemResource/LocalSvcTblDataSource/JdbcResource/LocalSvcTblDataSource/JDBCDriverParams/NO_NAME_0')
set('URL', 'jdbc:oracle:thin:@//$DB_HOSTPORT/$DB_SERVICE')
set('DriverName', 'oracle.jdbc.OracleDriver')
set('PasswordEncrypted', '$RCU_PASSWORD')
cd('Properties/NO_NAME_0/Property/user')
set('Value', '${RCU_PREFIX}_STB')
getDatabaseDefaults()
writeDomain('$DOMAIN_HOME')
closeTemplate()
PYEOF
  "$MW_HOME/oracle_common/common/bin/wlst.sh" /tmp/create_domain.py
  for s in AdminServer WLS_FORMS; do
    mkdir -p "$DOMAIN_HOME/servers/$s/security"
    printf 'username=weblogic\npassword=%s\n' "$WLS_PASSWORD" > "$DOMAIN_HOME/servers/$s/security/boot.properties"
  done
}

phase_component() {
  log "FORMS component instance forms1"
  # A WLST-built domain gets an incomplete forms1 instance: copy the product templates in
  # and resolve their %PLACEHOLDERS% the way the Config Wizard would.
  local inst=$FMWCONFIG/components/FORMS/forms1
  mkdir -p "$inst/server"
  cp -rn "$MW_HOME/forms/templates/config/." "$inst/server/"
  for f in "$inst"/server/*; do
    [ -f "$f" ] || continue
    sed -i -e "s|%ORACLE_HOME%|$MW_HOME|g" -e "s|%FORMS_INSTANCE%|$inst|g" -e "s|%FORMS_INSTANCE_NAME%|forms1|g" \
           -e "s|%DOMAIN_HOME%|$DOMAIN_HOME|g" -e "s|%JAVA_HOME%|$JAVA_HOME|g" \
           -e "s|%JRF_COMPONENT_HOME%|$MW_HOME/oracle_common|g" "$f"
  done
  # the key-mapping resources (fmrweb.res) frmweb needs; the Config Wizard would copy them
  (cd "$MW_HOME/forms/templates/resource" && for d in */; do cp -n "$d"fmr*web*.res "$MW_HOME/forms/admin/resource/$d" 2>/dev/null || true; done)
  # formsapp looks for the instance under components/FORMS/instances/forms1
  mkdir -p "$FMWCONFIG/components/FORMS/instances"
  ln -sfn "$inst" "$FMWCONFIG/components/FORMS/instances/forms1"
  grep -l '%[A-Z_]*%' "$inst"/server/* || echo "no placeholders left"
}

phase_config() {
  log "tnsnames APPDB + formsweb.cfg [orders]"
  local inst=$FMWCONFIG/components/FORMS/forms1
  # the servlet reads its config from the deployed formsapp, not from the instance
  local app=$FMWCONFIG/servers/WLS_FORMS/applications/formsapp_12.2.1/config
  grep -q '^APPDB' "$FMWCONFIG/tnsnames.ora" 2>/dev/null || cat >> "$FMWCONFIG/tnsnames.ora" <<TNS
APPDB =
  (DESCRIPTION = (ADDRESS = (PROTOCOL = TCP)(HOST = ${DB_HOSTPORT%%:*})(PORT = ${DB_HOSTPORT##*:}))
    (CONNECT_DATA = (SERVICE_NAME = $DB_SERVICE)))
TNS
  # Forms 12c's client needs Java 8; the stock template also asks for a JavaFX runtime we do not need
  grep -v 'javafx-runtime' "$inst/server/base.jnlp" > "$inst/server/orders.jnlp"
  grep -q '^\[orders\]' "$app/formsweb.cfg" || cat >> "$app/formsweb.cfg" <<CFG

# The Orders form, launched outside the browser through Java Web Start:
#   http://localhost:9001/forms/frmservlet?config=orders
[orders]
form=ORDERS.fmx
userid=app/app@APPDB
webstart=enabled
basejnlp=$inst/server/orders.jnlp
java_version=1.8*
baseHTML=$inst/server/base.htm
baseHTMLjpi=$inst/server/basejpi.htm
baseSAAfile=$inst/server/basesaa.txt
pageTitle=Orders
CFG
  mkdir -p /u01/forms-app
  grep -q '/u01/forms-app' "$app/default.env" || sed -i 's|^\(\s*FORMS_PATH=.*\)$|\1:/u01/forms-app|' "$app/default.env"
  grep -n 'FORMS_PATH=' "$app/default.env"
}

phase_start() {
  log "start AdminServer + WLS_FORMS"
  if ! curl -sf -o /dev/null http://localhost:7001/console; then
    nohup "$DOMAIN_HOME/bin/startWebLogic.sh" > "$DOMAIN_HOME/admin.out" 2>&1 &
    for _ in $(seq 1 180); do
      curl -s -o /dev/null -w '%{http_code}' http://localhost:7001/console | grep -q '200\|302' && break
      sleep 5
    done
  fi
  if ! curl -s http://localhost:9001/forms/frmservlet | grep -qi forms; then
    nohup "$DOMAIN_HOME/bin/startManagedWebLogic.sh" WLS_FORMS http://localhost:7001 > "$DOMAIN_HOME/wls_forms.out" 2>&1 &
    for _ in $(seq 1 180); do
      curl -s http://localhost:9001/forms/frmservlet | grep -qi forms && break
      sleep 5
    done
  fi
  curl -s -o /dev/null -w 'frmservlet -> HTTP %{http_code}\n' http://localhost:9001/forms/frmservlet
}

phases=("$@"); [ "${phases[0]:-}" = all ] && phases=(rcu domain component config start)
for p in "${phases[@]}"; do "phase_$p"; done
