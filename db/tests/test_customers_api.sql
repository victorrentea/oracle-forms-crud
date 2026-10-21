-- Package CUSTOMERS_API: validation, uniqueness, not-found, delete guard.
create or replace package test_customers_api as
  --%suite(Package CUSTOMERS_API)
  --%suitepath(whitebox)
  --%rollback(auto)

  --%context(create_customer)
  --%name(create_customer)

    --%test(creates an active customer with a trimmed name)
    procedure create_ok;
    --%test(credit limit 0 is allowed)
    procedure create_zero_limit;
    --%test(duplicate name is rejected)
    --%throws(-20007)
    procedure create_duplicate;
    --%test(name that is a duplicate only after trimming is rejected)
    --%throws(-20007)
    procedure create_duplicate_after_trim;
    --%test(blank name is rejected)
    --%throws(-20001)
    procedure create_blank_name;
    --%test(missing credit limit is rejected)
    --%throws(-20001)
    procedure create_null_limit;
    --%test(negative credit limit is rejected)
    --%throws(-20001)
    procedure create_negative_limit;

  --%endcontext

  --%context(update_customer)
  --%name(update_customer)

    --%test(updates name, credit limit and active flag)
    procedure update_ok;
    --%test(unknown customer is reported as not found)
    --%throws(-20006)
    procedure update_not_found;
    --%test(active flag other than Y/N is rejected)
    --%throws(-20001)
    procedure update_bad_active;
    --%test(missing active flag is rejected)
    --%throws(-20001)
    procedure update_null_active;
    --%test(blank name is rejected)
    --%throws(-20001)
    procedure update_blank_name;
    --%test(renaming to another customer's name is rejected)
    --%throws(-20007)
    procedure update_duplicate_name;

  --%endcontext

  --%context(delete_customer)
  --%name(delete_customer)

    --%test(customer without orders is deleted)
    procedure delete_ok;
    --%test(customer with orders cannot be deleted)
    --%throws(-20005)
    procedure delete_with_orders;
    --%test(customer with only CANCELLED orders still cannot be deleted)
    --%throws(-20005)
    procedure delete_with_cancelled_orders;
    --%test(unknown customer is reported as not found)
    --%throws(-20006)
    procedure delete_not_found;

  --%endcontext
end test_customers_api;
/

create or replace package body test_customers_api as
  c_missing_id constant number := -1;

  procedure create_ok is
    v_id  number;
    v_row customers%rowtype;
  begin
    customers_api.create_customer('  utPLSQL New Co  ', 2500, v_id);
    select * into v_row from customers where id = v_id;
    ut.expect(v_row.name).to_equal('utPLSQL New Co');
    ut.expect(v_row.credit_limit).to_equal(2500);
    ut.expect(v_row.active).to_equal('Y');
  end;

  procedure create_zero_limit is
    v_id    number;
    v_limit number;
  begin
    customers_api.create_customer('utPLSQL Zero Co', 0, v_id);
    select credit_limit into v_limit from customers where id = v_id;
    ut.expect(v_limit).to_equal(0);
  end;

  procedure create_duplicate is
    v_id number;
  begin
    customers_api.create_customer('utPLSQL Twin', 100, v_id);
    customers_api.create_customer('utPLSQL Twin', 200, v_id);
  end;

  procedure create_duplicate_after_trim is
    v_id number;
  begin
    customers_api.create_customer('utPLSQL Twin', 100, v_id);
    customers_api.create_customer(' utPLSQL Twin ', 200, v_id);
  end;

  procedure create_blank_name is
    v_id number;
  begin
    customers_api.create_customer('   ', 100, v_id);
  end;

  procedure create_null_limit is
    v_id number;
  begin
    customers_api.create_customer('utPLSQL No Limit', null, v_id);
  end;

  procedure create_negative_limit is
    v_id number;
  begin
    customers_api.create_customer('utPLSQL Negative', -1, v_id);
  end;

  procedure update_ok is
    v_id  number := test_fixture.new_customer('utPLSQL Before');
    v_row customers%rowtype;
  begin
    customers_api.update_customer(v_id, ' utPLSQL After ', 750, 'N');
    select * into v_row from customers where id = v_id;
    ut.expect(v_row.name).to_equal('utPLSQL After');
    ut.expect(v_row.credit_limit).to_equal(750);
    ut.expect(v_row.active).to_equal('N');
  end;

  procedure update_not_found is
  begin
    customers_api.update_customer(c_missing_id, 'utPLSQL Ghost', 100, 'Y');
  end;

  procedure update_bad_active is
  begin
    customers_api.update_customer(test_fixture.new_customer('utPLSQL Flag'), 'utPLSQL Flag', 100, 'X');
  end;

  procedure update_null_active is
  begin
    customers_api.update_customer(test_fixture.new_customer('utPLSQL Flag'), 'utPLSQL Flag', 100, null);
  end;

  procedure update_blank_name is
  begin
    customers_api.update_customer(test_fixture.new_customer('utPLSQL Named'), ' ', 100, 'Y');
  end;

  procedure update_duplicate_name is
    v_taken number := test_fixture.new_customer('utPLSQL Taken');
  begin
    customers_api.update_customer(test_fixture.new_customer('utPLSQL Other'), 'utPLSQL Taken', 100, 'Y');
  end;

  procedure delete_ok is
    v_id    number := test_fixture.new_customer('utPLSQL Doomed');
    v_count number;
  begin
    customers_api.delete_customer(v_id);
    select count(*) into v_count from customers where id = v_id;
    ut.expect(v_count).to_equal(0);
  end;

  procedure delete_with_orders is
    v_id    number := test_fixture.new_customer('utPLSQL Busy');
    v_order number := test_fixture.new_order(v_id);
  begin
    customers_api.delete_customer(v_id);
  end;

  procedure delete_with_cancelled_orders is
    v_id    number := test_fixture.new_customer('utPLSQL Was Busy');
    v_order number := test_fixture.new_order(v_id, p_status => 'CANCELLED');
  begin
    customers_api.delete_customer(v_id);
  end;

  procedure delete_not_found is
  begin
    customers_api.delete_customer(c_missing_id);
  end;
end test_customers_api;
/
