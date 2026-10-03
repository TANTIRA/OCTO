-- V46__pin_tracked_address_event_rules.sql
-- Pin tracked_address_event_rules() to the octo schema (#546).
--
-- V43 recreated the four functions whose stored bodies could still name mesta after the
-- schema-rename path. This trigger was left behind. Fresh databases already say octo,
-- because V28 rewrites every function body while it moves mesta. Renamed databases do not:
-- ops ran `alter schema mesta rename to octo` first, V28's loop returned immediately, and
-- the body still selects mesta.tracked_address_event and locks on 'mesta.tracked_address:'.
-- Every watch or unwatch then fails with "relation does not exist".
--
-- The body is V10's, with the qualifier changed. create or replace keeps the OID, so the
-- trigger stays attached. It also resets proconfig, so V37's search_path is set inline.

create or replace function octo.tracked_address_event_rules() returns trigger
    language plpgsql
    set search_path = pg_catalog, pg_temp
as $$
declare
    latest_type text;
    latest_at   timestamptz;
begin
    perform pg_advisory_xact_lock(
        hashtextextended('octo.tracked_address:' || new.chain || ':' || new.address, 0));

    select e.event_type, e.occurred_at
      into latest_type, latest_at
      from octo.tracked_address_event e
     where e.chain = new.chain and e.address = new.address
     order by e.seq desc
     limit 1;

    if new.event_type = 'watched' and latest_type = 'watched' then
        raise exception 'address % on % is already watched', new.address, new.chain
            using errcode = 'check_violation';
    end if;
    if new.event_type = 'unwatched' and latest_type is distinct from 'watched' then
        raise exception 'address % on % is not watched', new.address, new.chain
            using errcode = 'check_violation';
    end if;
    if new.occurred_at < latest_at then
        raise exception 'watch events of % on % must be in time order', new.address, new.chain
            using errcode = 'check_violation';
    end if;
    return new;
end;
$$;
