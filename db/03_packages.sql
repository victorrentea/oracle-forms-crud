-- The business logic. The UI may only call these procedures to change data.
-- Errors are raised as ORA-200xx with a user-facing message, like a Forms alert.
--   -20001 invalid input           -20002 credit limit exceeded
--   -20003 customer inactive       -20004 order not in a state that allows this
--   -20005 customer still has orders  -20006 record not found
--   -20007 duplicate customer name

create or replace package customers_api as
  procedure create_customer(p_name varchar2, p_credit_limit number, o_id out number);
  procedure update_customer(p_id number, p_name varchar2, p_credit_limit number, p_active varchar2);
  procedure delete_customer(p_id number);
end customers_api;
/

create or replace package body customers_api as

  procedure validate(p_name varchar2, p_credit_limit number) is
  begin
    if trim(p_name) is null then
      raise_application_error(-20001, 'Customer name is required');
    end if;
    if p_credit_limit is null or p_credit_limit < 0 then
      raise_application_error(-20001, 'Credit limit must be zero or positive');
    end if;
  end;

  procedure create_customer(p_name varchar2, p_credit_limit number, o_id out number) is
  begin
    validate(p_name, p_credit_limit);
    insert into customers (name, credit_limit) values (trim(p_name), p_credit_limit)
    returning id into o_id;
  exception
    when dup_val_on_index then
      raise_application_error(-20007, 'Customer "' || trim(p_name) || '" already exists');
  end;

  procedure update_customer(p_id number, p_name varchar2, p_credit_limit number, p_active varchar2) is
  begin
    validate(p_name, p_credit_limit);
    if nvl(p_active, '?') not in ('Y', 'N') then
      raise_application_error(-20001, 'Active must be Y or N');
    end if;
    update customers
       set name = trim(p_name), credit_limit = p_credit_limit, active = p_active
     where id = p_id;
    if sql%rowcount = 0 then
      raise_application_error(-20006, 'Customer ' || p_id || ' not found');
    end if;
  exception
    when dup_val_on_index then
      raise_application_error(-20007, 'Customer "' || trim(p_name) || '" already exists');
  end;

  procedure delete_customer(p_id number) is
    v_orders number;
  begin
    select count(*) into v_orders from orders where customer_id = p_id;
    if v_orders > 0 then
      raise_application_error(-20005, 'Customer has ' || v_orders || ' order(s) and cannot be deleted');
    end if;
    delete from customers where id = p_id;
    if sql%rowcount = 0 then
      raise_application_error(-20006, 'Customer ' || p_id || ' not found');
    end if;
  end;

end customers_api;
/

create or replace package orders_api as
  -- what the customer currently owes: NEW + SHIPPED orders; this is what the credit limit caps
  function exposure(p_customer_id number) return number;
  procedure create_order(p_customer_id number, p_product varchar2, p_quantity number,
                         p_unit_price number, o_id out number);
  procedure update_order(p_id number, p_product varchar2, p_quantity number, p_unit_price number);
  procedure ship_order(p_id number);
  procedure cancel_order(p_id number);
  procedure delete_order(p_id number);
end orders_api;
/

create or replace package body orders_api as

  procedure validate(p_product varchar2, p_quantity number, p_unit_price number) is
  begin
    if trim(p_product) is null then
      raise_application_error(-20001, 'Product is required');
    end if;
    if p_quantity is null or p_quantity <= 0 or p_quantity != trunc(p_quantity) then
      raise_application_error(-20001, 'Quantity must be a positive whole number');
    end if;
    if p_unit_price is null or p_unit_price <= 0 then
      raise_application_error(-20001, 'Unit price must be positive');
    end if;
  end;

  -- The order total is computed by trigger ORDERS_BIU, so the credit check runs after the
  -- row is written; callers roll back to a savepoint when it fails.
  function exposure(p_customer_id number) return number is
    v_exposure number;
  begin
    select nvl(sum(total), 0) into v_exposure
      from orders
     where customer_id = p_customer_id and status in ('NEW', 'SHIPPED');
    return v_exposure;
  end;

  procedure check_credit(p_customer_id number) is
    v_limit    customers.credit_limit%type;
    v_exposure number := exposure(p_customer_id);
  begin
    select credit_limit into v_limit from customers where id = p_customer_id;
    if v_exposure > v_limit then
      raise_application_error(-20002, 'Credit limit exceeded: open orders would total '
        || to_char(v_exposure, 'FM999999990.00') || ' but limit is '
        || to_char(v_limit, 'FM999999990.00'));
    end if;
  end;

  function lock_order(p_id number) return orders%rowtype is
    v_order orders%rowtype;
  begin
    select * into v_order from orders where id = p_id for update;
    return v_order;
  exception
    when no_data_found then
      raise_application_error(-20006, 'Order ' || p_id || ' not found');
  end;

  procedure create_order(p_customer_id number, p_product varchar2, p_quantity number,
                         p_unit_price number, o_id out number) is
    v_active customers.active%type;
  begin
    savepoint before_create;
    validate(p_product, p_quantity, p_unit_price);
    begin
      select active into v_active from customers where id = p_customer_id;
    exception
      when no_data_found then
        raise_application_error(-20006, 'Customer ' || p_customer_id || ' not found');
    end;
    if v_active = 'N' then
      raise_application_error(-20003, 'Customer is inactive and cannot place orders');
    end if;
    insert into orders (customer_id, product, quantity, unit_price)
    values (p_customer_id, trim(p_product), p_quantity, p_unit_price)
    returning id into o_id;
    check_credit(p_customer_id);
  exception
    when others then
      rollback to before_create;
      raise;
  end;

  procedure update_order(p_id number, p_product varchar2, p_quantity number, p_unit_price number) is
    v_order orders%rowtype;
  begin
    savepoint before_update;
    validate(p_product, p_quantity, p_unit_price);
    v_order := lock_order(p_id);
    if v_order.status != 'NEW' then
      raise_application_error(-20004, 'Only NEW orders can be changed (order is ' || v_order.status || ')');
    end if;
    update orders
       set product = trim(p_product), quantity = p_quantity, unit_price = p_unit_price
     where id = p_id;
    check_credit(v_order.customer_id);
  exception
    when others then
      rollback to before_update;
      raise;
  end;

  procedure ship_order(p_id number) is
    v_order orders%rowtype := lock_order(p_id);
  begin
    if v_order.status != 'NEW' then
      raise_application_error(-20004, 'Only NEW orders can be shipped (order is ' || v_order.status || ')');
    end if;
    update orders set status = 'SHIPPED' where id = p_id;
  end;

  procedure cancel_order(p_id number) is
    v_order orders%rowtype := lock_order(p_id);
  begin
    if v_order.status != 'NEW' then
      raise_application_error(-20004, 'Only NEW orders can be cancelled (order is ' || v_order.status || ')');
    end if;
    update orders set status = 'CANCELLED' where id = p_id;
  end;

  procedure delete_order(p_id number) is
    v_order orders%rowtype := lock_order(p_id);
  begin
    if v_order.status = 'SHIPPED' then
      raise_application_error(-20004, 'Shipped orders cannot be deleted');
    end if;
    delete from orders where id = p_id;
  end;

end orders_api;
/
