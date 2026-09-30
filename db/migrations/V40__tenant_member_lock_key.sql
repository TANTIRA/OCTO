-- V40__tenant_member_lock_key.sql
-- Pin tenant_member_event_rules() to the octo.* lock key and table references.
--
-- V8 took pg_advisory_xact_lock on 'mesta.tenant_member:<tenant>:<user>'; JdbcAccessStore
-- locks on 'octo.tenant_member:<tenant>:<user>' before it replays and appends, and both
-- claim to share one lock. How V28 reached each environment decides whether they do:
--   * Fresh databases: V28 recreated every function with 'mesta.' rewritten to 'octo.',
--     the lock literal included — the keys already match and this file is a no-op in effect.
--   * Renamed databases: ops ran `alter schema mesta rename to octo`, V28's loop skipped,
--     and the stored body still names the mesta lock key and mesta.tenant_member_event.
-- Recreating from one pinned body converges both. The body is V8's verbatim with the
-- qualifier changed; the OID is kept, so the trigger and the function comment carry over.
-- create or replace resets proconfig, so V37's search_path is re-applied inline.

create or replace function octo.tenant_member_event_rules() returns trigger
    language plpgsql
    set search_path = pg_catalog, pg_temp
as $$
declare
    latest_type text;
    latest_at   timestamptz;
    active      boolean;
begin
    -- One writer per membership at a time. JdbcAccessStore takes the same lock before it replays and appends.
    perform pg_advisory_xact_lock(
        hashtextextended('octo.tenant_member:' || new.tenant_id::text || ':' || new.user_id::text, 0));

    select e.event_type, e.occurred_at
      into latest_type, latest_at
      from octo.tenant_member_event e
     where e.tenant_id = new.tenant_id and e.user_id = new.user_id
     order by e.seq desc
     limit 1;
    active := latest_type in ('granted', 'role-changed');

    if new.event_type = 'granted' and coalesce(active, false) then
        raise exception 'user % already has access to tenant %; change the role instead', new.user_id, new.tenant_id
            using errcode = 'check_violation';
    end if;
    if new.event_type in ('role-changed', 'revoked') and not coalesce(active, false) then
        raise exception 'user % has no active membership in tenant %', new.user_id, new.tenant_id
            using errcode = 'check_violation';
    end if;
    if new.occurred_at < latest_at then
        raise exception 'membership events of % in tenant % must be in time order', new.user_id, new.tenant_id
            using errcode = 'check_violation';
    end if;
    -- data-security-governance.md: "No person may approve their own privileged access."
    if new.event_type in ('granted', 'role-changed') and new.actor = new.user_id::text then
        raise exception 'segregation of duties: % cannot grant or change their own access', new.user_id
            using errcode = 'check_violation';
    end if;
    return new;
end;
$$;
