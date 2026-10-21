-- Wipes all data and loads the known starting point.
--   sqlplus app/app@FREEPDB1 @seed.sql
whenever sqlerror exit failure
set feedback off
exec demo_data.reset
prompt Seed data loaded
exit
