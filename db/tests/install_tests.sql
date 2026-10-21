-- (Re)installs the utPLSQL test packages. Run as the APP user from this folder:
--   sqlplus app/app@FREEPDB1 @install_tests.sql
-- Drops every TEST_% package first, so a suite deleted from the repo disappears from the DB too.
whenever sqlerror exit failure
set feedback off
begin
  for p in (select object_name from user_objects
             where object_type = 'PACKAGE' and object_name like 'TEST\_%' escape '\') loop
    execute immediate 'drop package ' || p.object_name;
  end loop;
end;
/
@@test_fixture.sql
@@test_orders_biu.sql
@@test_orders_api.sql
@@test_customers_api.sql
@@test_audit_triggers.sql
@@test_known_bugs.sql
set serveroutput on
declare
  v_errors varchar2(4000);
begin
  for e in (select name, type, line, position, text from user_errors
             where name like 'TEST\_%' escape '\' order by name, type, sequence) loop
    dbms_output.put_line(e.type || ' ' || e.name || ' line ' || e.line || ':' || e.position || ' ' || e.text);
  end loop;
  select listagg(object_type || ' ' || object_name, ', ') into v_errors
    from user_objects where status != 'VALID' and object_name like 'TEST\_%' escape '\';
  if v_errors is not null then
    raise_application_error(-20000, 'Invalid objects after installing tests: ' || v_errors);
  end if;
end;
/
prompt Test packages installed OK
exit
