-- Fixture builders shared by the white-box suites. Not a suite itself.
-- Every row goes in through plain INSERTs inside the test's transaction, so
-- utPLSQL's --%rollback(auto) removes it again: no commit, no demo_data.reset (DDL).
create or replace package test_fixture as
  function new_customer(p_name varchar2, p_credit_limit number := 100000,
                        p_active varchar2 := 'Y') return number;
  -- status goes straight into the row, bypassing orders_api (which only creates NEW orders)
  function new_order(p_customer_id number, p_quantity number := 1, p_unit_price number := 10,
                     p_status varchar2 := 'NEW', p_product varchar2 := 'Widget') return number;
  -- highest audit_log.id so far: a test looks only at audit rows written after it
  function audit_mark return number;
  function audit_rows(p_table varchar2, p_row_id number, p_after number,
                      p_action varchar2 := null) return number;
end test_fixture;
/

create or replace package body test_fixture as

  function new_customer(p_name varchar2, p_credit_limit number := 100000,
                        p_active varchar2 := 'Y') return number is
    v_id customers.id%type;
  begin
    insert into customers (name, credit_limit, active)
    values (p_name, p_credit_limit, p_active)
    returning id into v_id;
    return v_id;
  end;

  function new_order(p_customer_id number, p_quantity number := 1, p_unit_price number := 10,
                     p_status varchar2 := 'NEW', p_product varchar2 := 'Widget') return number is
    v_id orders.id%type;
  begin
    insert into orders (customer_id, product, quantity, unit_price, status)
    values (p_customer_id, p_product, p_quantity, p_unit_price, p_status)
    returning id into v_id;
    return v_id;
  end;

  function audit_mark return number is
    v_max number;
  begin
    select nvl(max(id), 0) into v_max from audit_log;
    return v_max;
  end;

  function audit_rows(p_table varchar2, p_row_id number, p_after number,
                      p_action varchar2 := null) return number is
    v_count number;
  begin
    select count(*) into v_count
      from audit_log
     where table_name = p_table and row_id = p_row_id and id > p_after
       and (p_action is null or action = p_action);
    return v_count;
  end;

end test_fixture;
/
