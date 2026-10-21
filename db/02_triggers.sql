-- Derived fields and the audit trail live in triggers, the way Forms-era apps did it.

create or replace trigger orders_biu
before insert or update on orders
for each row
begin
  -- volume discount: 10+ items 5%, 50+ items 10%
  :new.discount_pct := case
                         when :new.quantity >= 50 then 10
                         when :new.quantity >= 10 then 5
                         else 0
                       end;
  :new.total := round(:new.quantity * :new.unit_price * (100 - :new.discount_pct) / 100, 2);
  if updating then
    :new.updated_at := sysdate;
  end if;
end;
/

create or replace trigger orders_audit
after insert or update or delete on orders
for each row
declare
  v_action audit_log.action%type;
  v_old    audit_log.old_values%type;
  v_new    audit_log.new_values%type;

  function fmt(p_customer_id number, p_product varchar2, p_quantity number,
               p_unit_price number, p_total number, p_status varchar2) return varchar2 is
  begin
    return 'customer_id=' || p_customer_id || ';product=' || p_product ||
           ';quantity=' || p_quantity || ';unit_price=' || to_char(p_unit_price, 'FM999999990.00') ||
           ';total=' || to_char(p_total, 'FM999999990.00') || ';status=' || p_status;
  end;
begin
  v_action := case when inserting then 'INSERT' when updating then 'UPDATE' else 'DELETE' end;
  if not inserting then
    v_old := fmt(:old.customer_id, :old.product, :old.quantity, :old.unit_price, :old.total, :old.status);
  end if;
  if not deleting then
    v_new := fmt(:new.customer_id, :new.product, :new.quantity, :new.unit_price, :new.total, :new.status);
  end if;
  insert into audit_log (table_name, row_id, action, old_values, new_values)
  values ('ORDERS', coalesce(:new.id, :old.id), v_action, v_old, v_new);
end;
/

create or replace trigger customers_audit
after insert or update or delete on customers
for each row
declare
  v_action audit_log.action%type;
  v_old    audit_log.old_values%type;
  v_new    audit_log.new_values%type;
begin
  v_action := case when inserting then 'INSERT' when updating then 'UPDATE' else 'DELETE' end;
  if not inserting then
    v_old := 'name=' || :old.name || ';credit_limit=' ||
             to_char(:old.credit_limit, 'FM999999990.00') || ';active=' || :old.active;
  end if;
  if not deleting then
    v_new := 'name=' || :new.name || ';credit_limit=' ||
             to_char(:new.credit_limit, 'FM999999990.00') || ';active=' || :new.active;
  end if;
  insert into audit_log (table_name, row_id, action, old_values, new_values)
  values ('CUSTOMERS', coalesce(:new.id, :old.id), v_action, v_old, v_new);
end;
/
