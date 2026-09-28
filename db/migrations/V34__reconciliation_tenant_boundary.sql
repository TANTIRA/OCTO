create unique index ledger_event_tenant_source_external_key
    on octo.ledger_event (tenant_id, source_system, external_id)
    where tenant_id is not null and external_id is not null;

create unique index ledger_event_shared_source_external_key
    on octo.ledger_event (source_system, external_id)
    where tenant_id is null and external_id is not null;

drop index octo.ledger_event_source_external_key;

alter table octo.ledger_event add constraint ledger_event_key_requires_tenant
    check (external_id is null or tenant_id is not null) not valid;

create function octo.ledger_event_tenant_rules() returns trigger
    language plpgsql
    set search_path = pg_catalog, pg_temp
as $$
declare
    parent_tenant uuid;
    parent_source text;
begin
    if new.supersedes_id is not null then
        select tenant_id, source_system into parent_tenant, parent_source
        from octo.ledger_event where id = new.supersedes_id;
        if not found or parent_tenant is distinct from new.tenant_id or parent_source is distinct from new.source_system then
            raise exception 'ledger correction must share tenant and source with its parent'
                using errcode = 'check_violation';
        end if;
    end if;
    return new;
end;
$$;

create trigger ledger_event_tenant_rules
    before insert on octo.ledger_event
    for each row execute function octo.ledger_event_tenant_rules();

do $$
begin
    if exists (select 1 from octo.ledger_event child join octo.ledger_event parent on parent.id = child.supersedes_id
               where child.tenant_id is distinct from parent.tenant_id
                  or child.source_system is distinct from parent.source_system) then
        raise exception 'existing ledger corrections cross tenant or source boundaries; review before migrating';
    end if;
    if exists (select 1 from octo.reconciliation_break b join octo.ledger_event e on e.id = b.ledger_event_id
               where e.tenant_id is distinct from b.tenant_id) then
        raise exception 'existing reconciliation breaks reference ledger events of another tenant; review before migrating';
    end if;
end;
$$;

create function octo.reconciliation_break_tenant_rules() returns trigger
    language plpgsql
    set search_path = pg_catalog, pg_temp
as $$
declare
    event_tenant uuid;
begin
    if new.ledger_event_id is not null then
        select tenant_id into event_tenant from octo.ledger_event where id = new.ledger_event_id;
        if not found or event_tenant is distinct from new.tenant_id then
            raise exception 'reconciliation break must reference a ledger event in its tenant'
                using errcode = 'check_violation';
        end if;
    end if;
    return new;
end;
$$;

create trigger reconciliation_break_tenant_rules
    before insert on octo.reconciliation_break
    for each row execute function octo.reconciliation_break_tenant_rules();
