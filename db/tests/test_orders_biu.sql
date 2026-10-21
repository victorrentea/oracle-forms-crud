-- Trigger ORDERS_BIU: derives discount_pct, total and updated_at on every insert/update.
create or replace package test_orders_biu as
  --%suite(Trigger ORDERS_BIU - derived discount and total)
  --%suitepath(whitebox)
  --%rollback(auto)

  --%beforeeach
  procedure setup;

  --%test(9 items get no discount)
  procedure qty_9_no_discount;
  --%test(10 items get 5%)
  procedure qty_10_five_pct;
  --%test(49 items still get 5%)
  procedure qty_49_five_pct;
  --%test(50 items get 10%)
  procedure qty_50_ten_pct;
  --%test(total = quantity * unit_price minus discount)
  procedure total_applies_discount;
  --%test(total is rounded half-up to 2 decimals)
  procedure total_rounded_half_up;
  --%test(total rounds down below the half cent)
  procedure total_rounded_down;
  --%test(discount and total supplied by the caller are overwritten)
  procedure caller_values_ignored;
  --%test(update recomputes discount and total)
  procedure update_recomputes;
  --%test(updated_at is empty on insert and set on update)
  procedure updated_at_only_on_update;
end test_orders_biu;
/

create or replace package body test_orders_biu as
  g_customer_id number;

  procedure setup is
  begin
    g_customer_id := test_fixture.new_customer('utPLSQL biu customer');
  end;

  function discount_for(p_quantity number) return number is
    v_id  number := test_fixture.new_order(g_customer_id, p_quantity, 10);
    v_pct orders.discount_pct%type;
  begin
    select discount_pct into v_pct from orders where id = v_id;
    return v_pct;
  end;

  procedure qty_9_no_discount  is begin ut.expect(discount_for(9)).to_equal(0);   end;
  procedure qty_10_five_pct    is begin ut.expect(discount_for(10)).to_equal(5);  end;
  procedure qty_49_five_pct    is begin ut.expect(discount_for(49)).to_equal(5);  end;
  procedure qty_50_ten_pct     is begin ut.expect(discount_for(50)).to_equal(10); end;

  function total_of(p_quantity number, p_unit_price number) return number is
    v_id    number := test_fixture.new_order(g_customer_id, p_quantity, p_unit_price);
    v_total orders.total%type;
  begin
    select total into v_total from orders where id = v_id;
    return v_total;
  end;

  procedure total_applies_discount is
  begin
    ut.expect(total_of(9, 20)).to_equal(180);     -- 0%
    ut.expect(total_of(12, 50)).to_equal(570);    -- 5%
    ut.expect(total_of(50, 10)).to_equal(450);    -- 10%
  end;

  procedure total_rounded_half_up is
  begin
    ut.expect(total_of(10, 0.99)).to_equal(9.41); -- 9.405
  end;

  procedure total_rounded_down is
  begin
    ut.expect(total_of(11, 1.01)).to_equal(10.55); -- 10.5545
  end;

  procedure caller_values_ignored is
    v_id  number;
    v_row orders%rowtype;
  begin
    insert into orders (customer_id, product, quantity, unit_price, discount_pct, total)
    values (g_customer_id, 'Widget', 5, 20, 99, 1)
    returning id into v_id;
    select * into v_row from orders where id = v_id;
    ut.expect(v_row.discount_pct).to_equal(0);
    ut.expect(v_row.total).to_equal(100);
  end;

  procedure update_recomputes is
    v_id  number := test_fixture.new_order(g_customer_id, 9, 10);
    v_row orders%rowtype;
  begin
    update orders set quantity = 50 where id = v_id;
    select * into v_row from orders where id = v_id;
    ut.expect(v_row.discount_pct).to_equal(10);
    ut.expect(v_row.total).to_equal(450);
  end;

  procedure updated_at_only_on_update is
    v_id      number := test_fixture.new_order(g_customer_id);
    v_updated date;
  begin
    select updated_at into v_updated from orders where id = v_id;
    ut.expect(v_updated).to_be_null();
    update orders set product = 'Gizmo' where id = v_id;
    select updated_at into v_updated from orders where id = v_id;
    ut.expect(v_updated).to_be_between(sysdate - 1/1440, sysdate);
  end;
end test_orders_biu;
/
