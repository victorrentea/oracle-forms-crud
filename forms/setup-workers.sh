#!/usr/bin/env bash
# Prepares N isolated workers for running the Forms UI tests in parallel. Worker i gets:
#   - its own schema APP<i> (password app) with the same tables, PL/SQL and seed data as APP
#   - its own Forms config [orders<i>] connecting as APP<i>, so its client never sees others' data
#   ./setup-workers.sh [N=3]      (re-runnable; needs the dev DB stack and the Forms container up)
set -euo pipefail
cd "$(dirname "$0")"
N=${1:-3}
DB=ofc-db-${STACK:-dev}
CFG=/u01/oracle/domains/forms_domain/config/fmwconfig/servers/WLS_FORMS/applications/formsapp_12.2.1/config/formsweb.cfg
INST=/u01/oracle/domains/forms_domain/config/fmwconfig/components/FORMS/forms1/server
for i in $(seq 1 "$N"); do
  podman exec -i "$DB" sqlplus -s system/oracle@FREEPDB1 >/dev/null <<SQL
declare
  n number;
begin
  select count(*) into n from dba_users where username = 'APP$i';
  if n = 0 then
    execute immediate 'create user app$i identified by app quota unlimited on users';
    execute immediate 'grant db_developer_role to app$i';
  end if;
end;
/
SQL
  podman exec -w /opt/ofc-db "$DB" sqlplus -s "app$i/app@FREEPDB1" @install.sql | tail -1
  podman exec -w /opt/ofc-db "$DB" sqlplus -s "app$i/app@FREEPDB1" @seed.sql | tail -1
  limactl shell forms nerdctl exec ofc-forms su oracle -c "grep -q '^\[orders$i\]' $CFG || cat >> $CFG <<CFGEOF

[orders$i]
form=ORDERS.fmx
userid=app$i/app@APPDB
webstart=enabled
basejnlp=$INST/orders.jnlp
java_version=1.8*
baseHTML=$INST/base.htm
baseHTMLjpi=$INST/basejpi.htm
baseSAAfile=$INST/basesaa.txt
pageTitle=Orders
width=1100
height=620
CFGEOF"
  echo "worker $i: schema APP$i, config [orders$i]"
done

# What each worker's Forms runtime sends to the database (queries, DML, package calls), for the
# test traces: top-level statements only, so DML inside orders_api is not repeated.
actions=""
for i in $(seq 1 "$N"); do
  for t in orders customers; do actions="$actions
  select on app$i.$t, insert on app$i.$t, update on app$i.$t, delete on app$i.$t,"; done
  actions="$actions
  execute on app$i.orders_api, execute on app$i.customers_api,"  # one line each: SQL*Plus caps line length
done
users=$(for i in $(seq 1 "$N"); do echo "app$i"; done | paste -sd, -)
podman exec -i "$DB" sqlplus -s system/oracle@FREEPDB1 <<SQL
whenever sqlerror continue
noaudit policy ofc_forms_trace;
drop audit policy ofc_forms_trace;
whenever sqlerror exit failure
create audit policy ofc_forms_trace actions ${actions%,} only toplevel;
audit policy ofc_forms_trace by $users;
SQL
echo "auditing what $users send to the database (policy OFC_FORMS_TRACE)"
