-- Triggers ORDERS_AUDIT and CUSTOMERS_AUDIT: one audit_log row per changed row,
-- with old/new values flattened into "key=value;..." strings.
create or replace package test_audit_triggers as
  --%suite(Audit triggers ORDERS_AUDIT and CUSTOMERS_AUDIT)
  --%suitepath(whitebox)
  --%rollback(auto)

  --%beforeeach
  procedure setup;

  --%test(order INSERT writes only new values)
  procedure order_insert;
  --%test(order UPDATE writes old and new values)
  procedure order_update;
  --%test(order DELETE writes only old values)
  procedure order_delete;
  --%test(customer INSERT writes only new values)
  procedure customer_insert;
  --%test(customer UPDATE writes old and new values)
  procedure customer_update;
  --%test(customer DELETE writes only old values)
  procedure customer_delete;
  --%test(every changed row gets its own audit row)
  procedure one_row_per_changed_row;
end test_audit_triggers;
/

create or replace package body test_audit_triggers as
  g_mark number;

  procedure setup is
  begin
    g_mark := test_fixture.audit_mark;
  end;

  function audit_of(p_table varchar2, p_row_id number, p_action varchar2) return audit_log%rowtype is
    v_row audit_log%rowtype;
  begin
    select * into v_row from audit_log
     where table_name = p_table and row_id = p_row_id and action = p_action and id > g_mark;
    return v_row;
  end;

  function order_string(p_customer_id number, p_rest varchar2) return varchar2 is
  begin
    return 'customer_id=' || p_customer_id || ';' || p_rest;
  end;

  procedure order_insert is
    v_customer number := test_fixture.new_customer('utPLSQL Audit Co');
    v_id       number := test_fixture.new_order(v_customer, 12, 50, 'NEW', 'Gadget');
    v_audit    audit_log%rowtype := audit_of('ORDERS', v_id, 'INSERT');
  begin
    ut.expect(v_audit.old_values).to_be_null();
    ut.expect(v_audit.new_values).to_equal(order_string(v_customer,
      'product=Gadget;quantity=12;unit_price=50.00;total=570.00;status=NEW'));
    ut.expect(v_audit.changed_by).to_equal(user);
    ut.expect(v_audit.changed_at).to_be_between(sysdate - 1/1440, sysdate);
  end;

  procedure order_update is
    v_customer number := test_fixture.new_customer('utPLSQL Audit Co');
    v_id       number := test_fixture.new_order(v_customer, 5, 20, 'NEW', 'Widget');
    v_audit    audit_log%rowtype;
  begin
    orders_api.ship_order(v_id);
    v_audit := audit_of('ORDERS', v_id, 'UPDATE');
    ut.expect(v_audit.old_values).to_equal(order_string(v_customer,
      'product=Widget;quantity=5;unit_price=20.00;total=100.00;status=NEW'));
    ut.expect(v_audit.new_values).to_equal(order_string(v_customer,
      'product=Widget;quantity=5;unit_price=20.00;total=100.00;status=SHIPPED'));
  end;

  procedure order_delete is
    v_customer number := test_fixture.new_customer('utPLSQL Audit Co');
    v_id       number := test_fixture.new_order(v_customer, 1, 0.5, 'CANCELLED', 'Bolt');
    v_audit    audit_log%rowtype;
  begin
    orders_api.delete_order(v_id);
    v_audit := audit_of('ORDERS', v_id, 'DELETE');
    ut.expect(v_audit.old_values).to_equal(order_string(v_customer,
      'product=Bolt;quantity=1;unit_price=0.50;total=0.50;status=CANCELLED'));
    ut.expect(v_audit.new_values).to_be_null();
  end;

  procedure customer_insert is
    v_id    number;
    v_audit audit_log%rowtype;
  begin
    customers_api.create_customer('utPLSQL Audit Co', 1234.5, v_id);
    v_audit := audit_of('CUSTOMERS', v_id, 'INSERT');
    ut.expect(v_audit.old_values).to_be_null();
    ut.expect(v_audit.new_values).to_equal('name=utPLSQL Audit Co;credit_limit=1234.50;active=Y');
  end;

  procedure customer_update is
    v_id    number := test_fixture.new_customer('utPLSQL Audit Co', 1000);
    v_audit audit_log%rowtype;
  begin
    customers_api.update_customer(v_id, 'utPLSQL Audit Inc', 0, 'N');
    v_audit := audit_of('CUSTOMERS', v_id, 'UPDATE');
    ut.expect(v_audit.old_values).to_equal('name=utPLSQL Audit Co;credit_limit=1000.00;active=Y');
    ut.expect(v_audit.new_values).to_equal('name=utPLSQL Audit Inc;credit_limit=0.00;active=N');
  end;

  procedure customer_delete is
    v_id    number := test_fixture.new_customer('utPLSQL Audit Co', 99.99, 'N');
    v_audit audit_log%rowtype;
  begin
    customers_api.delete_customer(v_id);
    v_audit := audit_of('CUSTOMERS', v_id, 'DELETE');
    ut.expect(v_audit.old_values).to_equal('name=utPLSQL Audit Co;credit_limit=99.99;active=N');
    ut.expect(v_audit.new_values).to_be_null();
  end;

  procedure one_row_per_changed_row is
    v_customer number := test_fixture.new_customer('utPLSQL Audit Co');
    v_id       number;
    v_count    number;
  begin
    v_id := test_fixture.new_order(v_customer);
    v_id := test_fixture.new_order(v_customer);
    v_id := test_fixture.new_order(v_customer);
    update orders set product = 'Renamed' where customer_id = v_customer;
    select count(*) into v_count from audit_log
     where table_name = 'ORDERS' and action = 'UPDATE' and id > g_mark;
    ut.expect(v_count).to_equal(3);
  end;
end test_audit_triggers;
/
