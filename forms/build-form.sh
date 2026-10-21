#!/usr/bin/env bash
# Rebuilds the Orders form inside the Forms container: src/ORDERS.xml -> ORDERS.fmb (frmxml2f)
# -> ORDERS.fmx (frmcmp_batch, which also checks every trigger against the live schema).
# New Forms sessions pick up the new .fmx; restart the client to see it.
set -euo pipefail
cd "$(dirname "$0")"
limactl copy src/ORDERS.xml forms:/tmp/ORDERS.xml
limactl shell forms nerdctl cp /tmp/ORDERS.xml ofc-forms:/tmp/ORDERS.xml
limactl shell forms nerdctl exec ofc-forms bash -c 'install -o oracle -m 644 /tmp/ORDERS.xml /u01/forms-app/ORDERS.xml && su oracle -c "
  set -e; cd /u01/forms-app
  export ORACLE_HOME=/u01/oracle/middleware JAVA_HOME=/u01/jdk8 FORMS_API_TK_BYPASS=TRUE TERM=vt220 ORACLE_TERM=vt220
  export FORMS_INSTANCE=/u01/oracle/domains/forms_domain/config/fmwconfig/components/FORMS/forms1
  export TNS_ADMIN=/u01/oracle/domains/forms_domain/config/fmwconfig
  S=\$ORACLE_HOME/forms/templates/scripts
  \$S/frmxml2f.sh OVERWRITE=YES ORDERS.xml | tail -1
  \$S/frmcmp_batch.sh module=ORDERS.fmb userid=app/app@APPDB module_type=form compile_all=yes output_file=ORDERS.fmx </dev/null | grep -E \"Created|rror\"
"'
