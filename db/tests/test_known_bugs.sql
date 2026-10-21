-- Real defects found while writing the white-box tests. Each test states the CORRECT
-- behaviour, so it FAILS today. Tagged known_bug: whitebox.sh skips them unless
-- KNOWN_BUGS=1. When a fix lands, its test turns green: move it to its package's suite.
create or replace package test_known_bugs as
  --%suite(Known bugs - expected to FAIL until fixed)
  --%suitepath(whitebox)
  --%tags(known_bug)
  --%rollback(auto)

  --%beforeeach
  procedure setup;

  --%test(unit price below one cent is rejected, not silently stored as 0.00)
  --%throws(-20001)
  procedure sub_cent_price_rejected;

  --%test(audit keeps a credit limit of 1 billion or more readable)
  procedure audit_big_credit_limit;

  --%test(audit keeps an order total of 1 billion or more readable)
  procedure audit_big_order_total;

  --%test(quantity too large for the column is a validation error, not ORA-01438)
  --%throws(-20001)
  procedure oversized_quantity_rejected;

  --%test(product name longer than 100 chars is a validation error, not ORA-12899)
  --%throws(-20001)
  procedure oversized_product_rejected;
end test_known_bugs;
/

create or replace package body test_known_bugs as
  g_customer_id number;

  procedure setup is
  begin
    g_customer_id := test_fixture.new_customer('utPLSQL known bugs customer', 9999999999);
  end;

  -- validate() checks p_unit_price > 0 BEFORE the value is rounded into number(10,2):
  -- 0.004 passes, is stored as 0.00, and the order is free (total 0.00).
  procedure sub_cent_price_rejected is
    v_id number;
  begin
    orders_api.create_order(g_customer_id, 'Widget', 1, 0.004, v_id);
  end;

  -- the audit triggers format money with 'FM999999990.00' (9 integer digits), but
  -- credit_limit/total are number(12,2) (10 integer digits): the audit row gets '#####'.
  procedure audit_big_credit_limit is
    v_mark   number := test_fixture.audit_mark;
    v_id     number;
    v_values audit_log.new_values%type;
  begin
    customers_api.create_customer('utPLSQL Big Spender', 1000000000, v_id);
    select new_values into v_values from audit_log
     where table_name = 'CUSTOMERS' and row_id = v_id and id > v_mark;
    ut.expect(v_values).to_equal('name=utPLSQL Big Spender;credit_limit=1000000000.00;active=Y');
  end;

  procedure audit_big_order_total is
    v_mark   number := test_fixture.audit_mark;
    v_id     number;
    v_values audit_log.new_values%type;
  begin
    orders_api.create_order(g_customer_id, 'Yacht', 20, 60000000, v_id);  -- 1,140,000,000.00 after 5%
    select new_values into v_values from audit_log
     where table_name = 'ORDERS' and row_id = v_id and id > v_mark;
    ut.expect(v_values).to_be_like('%;total=1140000000.00;%');
  end;

  procedure oversized_quantity_rejected is
    v_id number;
  begin
    orders_api.create_order(g_customer_id, 'Widget', 1000000, 1, v_id);
  end;

  procedure oversized_product_rejected is
    v_id number;
  begin
    orders_api.create_order(g_customer_id, rpad('x', 101, 'x'), 1, 1, v_id);
  end;
end test_known_bugs;
/
