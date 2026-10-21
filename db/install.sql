-- Installs (or re-installs from scratch) the whole schema. Run as the APP user:
--   sqlplus app/app@FREEPDB1 @install.sql
whenever sqlerror exit failure
set feedback off
@@01_schema.sql
@@02_triggers.sql
@@03_packages.sql
@@04_demo_data.sql
set feedback on
declare
  v_invalid varchar2(4000);
begin
  select listagg(object_type || ' ' || object_name, ', ') into v_invalid
    from user_objects where status != 'VALID';
  if v_invalid is not null then
    raise_application_error(-20000, 'Invalid objects after install: ' || v_invalid);
  end if;
end;
/
prompt Schema installed OK
exit
