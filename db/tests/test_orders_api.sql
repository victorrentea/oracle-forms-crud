-- Package ORDERS_API: validation, credit check, status transitions, exposure.
create or replace package test_orders_api as
  --%suite(Package ORDERS_API)
  --%suitepath(whitebox)
  --%rollback(auto)

  --%beforeeach
  procedure setup;

  --%context(create_order)
  --%name(create_order)

    --%test(creates a NEW order with trimmed product and derived total)
    procedure create_ok;
    --%test(blank product is rejected)
    --%throws(-20001)
    procedure create_blank_product;
    --%test(quantity 0 is rejected)
    --%throws(-20001)
    procedure create_qty_zero;
    --%test(fractional quantity 1.5 is rejected)
    --%throws(-20001)
    procedure create_qty_fraction;
    --%test(missing quantity is rejected)
    --%throws(-20001)
    procedure create_qty_null;
    --%test(unit price 0 is rejected)
    --%throws(-20001)
    procedure create_price_zero;
    --%test(negative unit price is rejected)
    --%throws(-20001)
    procedure create_price_negative;
    --%test(inactive customer cannot order)
    --%throws(-20003)
    procedure create_inactive_customer;
    --%test(unknown customer is reported as not found)
    --%throws(-20006)
    procedure create_unknown_customer;
    --%test(order that exceeds the credit limit fails and leaves no order and no audit row)
    procedure create_over_credit_limit;
    --%test(order that reaches the credit limit exactly is accepted)
    procedure create_exactly_at_limit;
    --%test(a failed create keeps the caller's earlier uncommitted work)
    procedure create_failure_keeps_earlier_work;

  --%endcontext

  --%context(update_order)
  --%name(update_order)

    --%test(updates product, quantity and price and recomputes the total)
    procedure update_ok;
    --%test(SHIPPED order cannot be changed)
    --%throws(-20004)
    procedure update_shipped;
    --%test(CANCELLED order cannot be changed)
    --%throws(-20004)
    procedure update_cancelled;
    --%test(unknown order is reported as not found)
    --%throws(-20006)
    procedure update_not_found;
    --%test(invalid input is rejected)
    --%throws(-20001)
    procedure update_invalid;
    --%test(update over the credit limit fails and restores the previous values, no audit row)
    procedure update_over_credit_limit;

  --%endcontext

  --%context(ship_and_cancel)
  --%name(ship_and_cancel)

    --%test(ship moves NEW to SHIPPED)
    procedure ship_new;
    --%test(shipping a SHIPPED order is illegal)
    --%throws(-20004)
    procedure ship_shipped;
    --%test(shipping a CANCELLED order is illegal)
    --%throws(-20004)
    procedure ship_cancelled;
    --%test(shipping an unknown order is reported as not found)
    --%throws(-20006)
    procedure ship_not_found;
    --%test(cancel moves NEW to CANCELLED)
    procedure cancel_new;
    --%test(cancelling a SHIPPED order is illegal)
    --%throws(-20004)
    procedure cancel_shipped;
    --%test(cancelling a CANCELLED order is illegal)
    --%throws(-20004)
    procedure cancel_cancelled;
    --%test(cancelling an unknown order is reported as not found)
    --%throws(-20006)
    procedure cancel_not_found;

  --%endcontext

  --%context(delete_order)
  --%name(delete_order)

    --%test(NEW order can be deleted)
    procedure delete_new;
    --%test(CANCELLED order can be deleted)
    procedure delete_cancelled;
    --%test(SHIPPED order cannot be deleted)
    --%throws(-20004)
    procedure delete_shipped;
    --%test(deleting an unknown order is reported as not found)
    --%throws(-20006)
    procedure delete_not_found;

  --%endcontext

  --%context(exposure)
  --%name(exposure)

    --%test(sums NEW and SHIPPED totals, ignores CANCELLED)
    procedure exposure_new_and_shipped_only;
    --%test(is 0 for a customer without orders)
    procedure exposure_no_orders;
    --%test(cancelling an order frees credit for a new one)
    procedure cancel_frees_credit;

  --%endcontext
end test_orders_api;
/

create or replace package body test_orders_api as
  g_customer_id number;   -- active, credit limit 1000
  c_missing_id  constant number := -1;

  procedure setup is
  begin
    g_customer_id := test_fixture.new_customer('utPLSQL orders customer', 1000);
  end;

  function order_count(p_customer_id number) return number is
    v_count number;
  begin
    select count(*) into v_count from orders where customer_id = p_customer_id;
    return v_count;
  end;

  function status_of(p_id number) return varchar2 is
    v_status orders.status%type;
  begin
    select status into v_status from orders where id = p_id;
    return v_status;
  end;

  -- create_order ------------------------------------------------------------

  procedure create_ok is
    v_id  number;
    v_row orders%rowtype;
  begin
    orders_api.create_order(g_customer_id, '  Widget  ', 10, 20, v_id);
    select * into v_row from orders where id = v_id;
    ut.expect(v_row.customer_id).to_equal(g_customer_id);
    ut.expect(v_row.product).to_equal('Widget');
    ut.expect(v_row.status).to_equal('NEW');
    ut.expect(v_row.discount_pct).to_equal(5);
    ut.expect(v_row.total).to_equal(190);
  end;

  procedure create_blank_product is
    v_id number;
  begin
    orders_api.create_order(g_customer_id, '   ', 1, 10, v_id);
  end;

  procedure create_qty_zero is
    v_id number;
  begin
    orders_api.create_order(g_customer_id, 'Widget', 0, 10, v_id);
  end;

  procedure create_qty_fraction is
    v_id number;
  begin
    orders_api.create_order(g_customer_id, 'Widget', 1.5, 10, v_id);
  end;

  procedure create_qty_null is
    v_id number;
  begin
    orders_api.create_order(g_customer_id, 'Widget', null, 10, v_id);
  end;

  procedure create_price_zero is
    v_id number;
  begin
    orders_api.create_order(g_customer_id, 'Widget', 1, 0, v_id);
  end;

  procedure create_price_negative is
    v_id number;
  begin
    orders_api.create_order(g_customer_id, 'Widget', 1, -5, v_id);
  end;

  procedure create_inactive_customer is
    v_customer number := test_fixture.new_customer('utPLSQL inactive customer', 1000, 'N');
    v_id       number;
  begin
    orders_api.create_order(v_customer, 'Widget', 1, 10, v_id);
  end;

  procedure create_unknown_customer is
    v_id number;
  begin
    orders_api.create_order(c_missing_id, 'Widget', 1, 10, v_id);
  end;

  procedure create_over_credit_limit is
    v_first  number;
    v_id     number;
    v_mark   number;
  begin
    orders_api.create_order(g_customer_id, 'Widget', 9, 100, v_first);   -- 900 of 1000
    v_mark := test_fixture.audit_mark;
    begin
      orders_api.create_order(g_customer_id, 'Gadget', 1, 100.01, v_id); -- 1000.01
      ut.fail('expected ORA-20002 credit limit exceeded');
    exception
      when others then
        ut.expect(sqlcode).to_equal(-20002);
        ut.expect(sqlerrm).to_be_like('%open orders would total 1000.01 but limit is 1000.00%');
    end;
    ut.expect(order_count(g_customer_id)).to_equal(1);
    ut.expect(test_fixture.audit_mark).to_equal(v_mark);
  end;

  procedure create_exactly_at_limit is
    v_id number;
  begin
    orders_api.create_order(g_customer_id, 'Widget', 10, 100, v_id);  -- 950
    orders_api.create_order(g_customer_id, 'Gadget', 1, 50, v_id);    -- 1000 = limit
    ut.expect(orders_api.exposure(g_customer_id)).to_equal(1000);
  end;

  procedure create_failure_keeps_earlier_work is
    v_other number := test_fixture.new_customer('utPLSQL bystander', 1);
    v_id    number;
  begin
    begin
      orders_api.create_order(g_customer_id, 'Widget', 1, 5000, v_id);
      ut.fail('expected ORA-20002');
    exception
      when others then ut.expect(sqlcode).to_equal(-20002);
    end;
    -- the savepoint inside create_order must not undo what the caller did before
    ut.expect(test_fixture.audit_rows('CUSTOMERS', v_other, 0, 'INSERT')).to_equal(1);
    ut.expect(order_count(g_customer_id)).to_equal(0);
  end;

  -- update_order ------------------------------------------------------------

  procedure update_ok is
    v_id  number := test_fixture.new_order(g_customer_id, 1, 10);
    v_row orders%rowtype;
  begin
    orders_api.update_order(v_id, ' Gizmo ', 50, 2);
    select * into v_row from orders where id = v_id;
    ut.expect(v_row.product).to_equal('Gizmo');
    ut.expect(v_row.quantity).to_equal(50);
    ut.expect(v_row.unit_price).to_equal(2);
    ut.expect(v_row.discount_pct).to_equal(10);
    ut.expect(v_row.total).to_equal(90);
    ut.expect(v_row.status).to_equal('NEW');
  end;

  procedure update_shipped is
  begin
    orders_api.update_order(test_fixture.new_order(g_customer_id, p_status => 'SHIPPED'), 'Widget', 2, 10);
  end;

  procedure update_cancelled is
  begin
    orders_api.update_order(test_fixture.new_order(g_customer_id, p_status => 'CANCELLED'), 'Widget', 2, 10);
  end;

  procedure update_not_found is
  begin
    orders_api.update_order(c_missing_id, 'Widget', 2, 10);
  end;

  procedure update_invalid is
  begin
    orders_api.update_order(test_fixture.new_order(g_customer_id), 'Widget', 0, 10);
  end;

  procedure update_over_credit_limit is
    v_id   number := test_fixture.new_order(g_customer_id, 5, 100);  -- 500
    v_mark number := test_fixture.audit_mark;
    v_row  orders%rowtype;
  begin
    begin
      orders_api.update_order(v_id, 'Gadget', 11, 100);              -- 1045
      ut.fail('expected ORA-20002 credit limit exceeded');
    exception
      when others then ut.expect(sqlcode).to_equal(-20002);
    end;
    select * into v_row from orders where id = v_id;
    ut.expect(v_row.product).to_equal('Widget');
    ut.expect(v_row.quantity).to_equal(5);
    ut.expect(v_row.total).to_equal(500);
    ut.expect(v_row.updated_at).to_be_null();
    ut.expect(test_fixture.audit_mark).to_equal(v_mark);
  end;

  -- ship / cancel -----------------------------------------------------------

  procedure ship_new is
    v_id number := test_fixture.new_order(g_customer_id);
  begin
    orders_api.ship_order(v_id);
    ut.expect(status_of(v_id)).to_equal('SHIPPED');
  end;

  procedure ship_shipped is
  begin
    orders_api.ship_order(test_fixture.new_order(g_customer_id, p_status => 'SHIPPED'));
  end;

  procedure ship_cancelled is
  begin
    orders_api.ship_order(test_fixture.new_order(g_customer_id, p_status => 'CANCELLED'));
  end;

  procedure ship_not_found is
  begin
    orders_api.ship_order(c_missing_id);
  end;

  procedure cancel_new is
    v_id number := test_fixture.new_order(g_customer_id);
  begin
    orders_api.cancel_order(v_id);
    ut.expect(status_of(v_id)).to_equal('CANCELLED');
  end;

  procedure cancel_shipped is
  begin
    orders_api.cancel_order(test_fixture.new_order(g_customer_id, p_status => 'SHIPPED'));
  end;

  procedure cancel_cancelled is
  begin
    orders_api.cancel_order(test_fixture.new_order(g_customer_id, p_status => 'CANCELLED'));
  end;

  procedure cancel_not_found is
  begin
    orders_api.cancel_order(c_missing_id);
  end;

  -- delete_order ------------------------------------------------------------

  procedure delete_new is
    v_id number := test_fixture.new_order(g_customer_id);
  begin
    orders_api.delete_order(v_id);
    ut.expect(order_count(g_customer_id)).to_equal(0);
  end;

  procedure delete_cancelled is
    v_id number := test_fixture.new_order(g_customer_id, p_status => 'CANCELLED');
  begin
    orders_api.delete_order(v_id);
    ut.expect(order_count(g_customer_id)).to_equal(0);
  end;

  procedure delete_shipped is
  begin
    orders_api.delete_order(test_fixture.new_order(g_customer_id, p_status => 'SHIPPED'));
  end;

  procedure delete_not_found is
  begin
    orders_api.delete_order(c_missing_id);
  end;

  -- exposure ----------------------------------------------------------------

  procedure exposure_new_and_shipped_only is
    v_id number;
  begin
    v_id := test_fixture.new_order(g_customer_id, 1, 100, 'NEW');
    v_id := test_fixture.new_order(g_customer_id, 1, 20,  'SHIPPED');
    v_id := test_fixture.new_order(g_customer_id, 1, 3,   'CANCELLED');
    ut.expect(orders_api.exposure(g_customer_id)).to_equal(120);
  end;

  procedure exposure_no_orders is
  begin
    ut.expect(orders_api.exposure(g_customer_id)).to_equal(0);
  end;

  procedure cancel_frees_credit is
    v_big number;
    v_id  number;
  begin
    orders_api.create_order(g_customer_id, 'Widget', 1, 900, v_big);
    orders_api.cancel_order(v_big);
    orders_api.create_order(g_customer_id, 'Gadget', 1, 900, v_id);
    ut.expect(orders_api.exposure(g_customer_id)).to_equal(900);
  end;
end test_orders_api;
/
