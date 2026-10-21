#!/usr/bin/env bash
# Records what the application asks of the database, to replay it later as a golden master.
# Uses Oracle Unified Auditing, so it sees every client the same way: this web app, an Oracle
# Forms runtime, SQL*Plus. It records each top-level call to the business packages and each
# DML statement sent straight to the tables (what Forms base-table blocks do), with bind values.
#   ./capture.sh start [stack]   clear old captures and start recording
#   ./capture.sh dump [stack]    print the recording (redirect it into a .rec file)
#   ./capture.sh stop [stack]    stop recording
set -euo pipefail
cd "$(dirname "$0")"

ENGINE=${ENGINE:-podman}
CMD=${1:-help}
DB=ofc-db-${2:-dev}
sys_sql() { $ENGINE exec -i "$DB" sqlplus -s system/oracle@FREEPDB1; }

case "$CMD" in
  start) sys_sql <<'SQL' >/dev/null
whenever sqlerror continue
noaudit policy ofc_capture by app;
drop audit policy ofc_capture;
whenever sqlerror exit failure
exec dbms_audit_mgmt.clean_audit_trail(audit_trail_type => dbms_audit_mgmt.audit_trail_unified, use_last_arch_timestamp => false)
create audit policy ofc_capture actions
  execute on app.orders_api, execute on app.customers_api,
  insert on app.orders, update on app.orders, delete on app.orders,
  insert on app.customers, update on app.customers, delete on app.customers
  only toplevel;
audit policy ofc_capture by app;
SQL
         echo "Recording calls made by user APP in $DB" >&2 ;;
  dump)  echo "# recorded $(date -u +%Y-%m-%dT%H:%M:%SZ) from $DB: <sql>TAB<binds>, with \\\\ \\n \\r \\t escaped"
         sys_sql <<'SQL'
set long 1000000 longchunksize 32767 linesize 32767 pagesize 0 trimspool on feedback off heading off
select replace(replace(replace(replace(dbms_lob.substr(sql_text, 4000), '\', '\\'), chr(10), '\n'), chr(13), '\r'), chr(9), '\t')
       || chr(9) ||
       replace(replace(replace(replace(dbms_lob.substr(sql_binds, 4000), '\', '\\'), chr(10), '\n'), chr(13), '\r'), chr(9), '\t')
  from unified_audit_trail
 where unified_audit_policies like '%OFC_CAPTURE%'
   and upper(ltrim(dbms_lob.substr(sql_text, 20))) not like 'SELECT%' -- queries calling a package function
 order by event_timestamp, entry_id;
SQL
         ;;
  stop)  sys_sql <<'SQL' >/dev/null
noaudit policy ofc_capture by app;
SQL
         echo "Stopped recording in $DB" >&2 ;;
  *)     sed -n '2,9p' "$0" ;;
esac
