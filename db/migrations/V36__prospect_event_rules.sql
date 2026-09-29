-- V36__prospect_event_rules.sql
-- #265: the full Prospect.next() transition rules in the database.
--
-- V18/V24/V26 check each row's shape and direction, but the state machine — stage_from must be the
-- replayed stage, advances move exactly one stage, terminal stages accept nothing, occurred_at never
-- goes backwards — lived only in Kotlin. prospect_event is append-only, so one hand-written row that
-- replay() rejects leaves the prospect unreadable for good; the runtime role holds INSERT. With this
-- trigger the database accepts exactly the histories replay() accepts, because the advisory lock
-- below makes the checks see every earlier event of the prospect — the same contract V7 gives tasks.

create function octo.prospect_event_rules() returns trigger
    language plpgsql
    set search_path = pg_catalog, pg_temp
as $$
declare
    rank          constant text[] := array['sourced', 'screening', 'due-diligence', 'ic-review', 'invested', 'passed'];
    current_stage text;
    latest_at     timestamptz;
begin
    -- One writer per prospect at a time. JdbcProspectStore.replayLocked takes the same lock.
    perform pg_catalog.pg_advisory_xact_lock(hashtextextended('octo.prospect:' || new.prospect_id::text, 0));

    -- The replayed stage is the latest event's landing, or 'sourced' before any event. seq is append
    -- order: replay folds by seq because occurred_at can tie.
    select e.stage_to, e.occurred_at into current_stage, latest_at
    from octo.prospect_event e
    where e.prospect_id = new.prospect_id
    order by e.seq desc
    limit 1;
    current_stage := coalesce(current_stage, 'sourced');

    if latest_at is null then
        select p.registered_at into latest_at from octo.prospect p where p.id = new.prospect_id;
    end if;

    -- Prospect.next() refuses everything once a decision landed.
    if current_stage in ('invested', 'passed') then
        raise exception 'prospect % is already %', new.prospect_id, current_stage using errcode = 'check_violation';
    end if;

    -- The business clock cannot rewind past the previous event — or, for the first one, registration.
    if new.occurred_at < latest_at then
        raise exception 'events of prospect % must be in time order: % is before %',
            new.prospect_id, new.occurred_at, latest_at using errcode = 'check_violation';
    end if;

    -- Every transition names the stage it leaves; insertEvent always writes the replayed stage.
    if new.stage_from <> current_stage then
        raise exception 'prospect % is %, not %', new.prospect_id, current_stage, new.stage_from
            using errcode = 'check_violation';
    end if;

    if new.event_type = 'advanced' then
        if array_position(rank, new.stage_to) <> array_position(rank, new.stage_from) + 1 then
            raise exception '% is not the stage after % — the pipeline moves one stage at a time',
                new.stage_to, new.stage_from using errcode = 'check_violation';
        end if;
        if new.stage_to in ('invested', 'passed') then
            raise exception 'advance reaches only open stages; decide with invested or passed'
                using errcode = 'check_violation';
        end if;
    end if;

    if new.event_type = 'invested' and current_stage <> 'ic-review' then
        raise exception 'only ic-review can invest; prospect % is %', new.prospect_id, current_stage
            using errcode = 'check_violation';
    end if;

    return new;
end;
$$;

comment on function octo.prospect_event_rules() is
    'Enforces Prospect.next(): terminal stages accept nothing, stage_from is the replayed stage, advances move one stage, invested needs ic-review, events stay in time order.';

create trigger prospect_event_rules
    before insert on octo.prospect_event
    for each row execute function octo.prospect_event_rules();
