-- V7__workflow_task_rules.sql
-- The full Task.next() transition rules in the database (issue #50, review of #56).
--
-- V5 enforces segregation of duties and one terminal decision, but a caller writing rows directly can still store a
-- history that Task.replay() rejects: an event after the decision, the completion of an approval (which lets a
-- requester close their own approval with no decision and no rationale), a decision on a review task, a decision
-- while the task is in rework, or an event dated before the previous one. Both tables are append-only, so one such
-- row leaves the task unreadable for good. With these rules the database accepts exactly the histories replay()
-- accepts, because the advisory lock below makes the checks see every earlier event of the task.

alter table mesta.workflow_task_event add column seq bigint generated always as identity;

comment on column mesta.workflow_task_event.seq is
    'Append order. Replay a task''s events by seq: occurred_at can tie, and ties would make replay depend on luck.';

create index workflow_task_event_task_seq_idx on mesta.workflow_task_event (task_id, seq);

-- Replaces V5's body; V5's segregation-of-duties checks are kept as they were, at the end.
create or replace function mesta.workflow_task_event_segregation() returns trigger
    language plpgsql
as $$
declare
    t         mesta.workflow_task;
    decisions constant text[] := array['approved', 'rejected', 'rework-requested'];
    terminal  constant text[] := array['approved', 'rejected', 'completed', 'cancelled'];
    latest_at timestamptz;
    in_rework boolean;
begin
    -- One writer per task at a time. JdbcTaskStore takes the same lock before it replays and appends.
    perform pg_advisory_xact_lock(hashtextextended('mesta.workflow_task:' || new.task_id::text, 0));
    select * into t from mesta.workflow_task where id = new.task_id;

    -- A second terminal event is V5's unique index's to refuse; anything else after a decision is refused here.
    if not (new.event_type = any (terminal)) and exists (
        select 1 from mesta.workflow_task_event e where e.task_id = new.task_id and e.event_type = any (terminal)
    ) then
        raise exception 'task % is already decided', t.id using errcode = 'check_violation';
    end if;

    select max(e.occurred_at) into latest_at from mesta.workflow_task_event e where e.task_id = new.task_id;
    if new.occurred_at < latest_at then
        raise exception 'events of task % must be in time order: % is before %', t.id, new.occurred_at, latest_at
            using errcode = 'check_violation';
    end if;

    if t.kind = 'approval' and new.event_type = 'completed' then
        raise exception 'approval task % ends in a decision, not a completion', t.id using errcode = 'check_violation';
    end if;
    if t.kind <> 'approval' and (new.event_type = any (decisions) or new.event_type = 'resubmitted') then
        raise exception 'only approval tasks are approved, rejected, sent back or resubmitted; % is a % task', t.id, t.kind
            using errcode = 'check_violation';
    end if;

    select e.event_type = 'rework-requested' into in_rework
    from mesta.workflow_task_event e
    where e.task_id = new.task_id and e.event_type in ('rework-requested', 'resubmitted')
    order by e.seq desc
    limit 1;
    if new.event_type = any (decisions) and coalesce(in_rework, false) then
        raise exception 'task % is in rework and must be resubmitted before a decision', t.id using errcode = 'check_violation';
    end if;
    if new.event_type = 'resubmitted' and not coalesce(in_rework, false) then
        raise exception 'only a task sent back for rework can be resubmitted; % is not', t.id using errcode = 'check_violation';
    end if;

    if t.kind = 'approval' then
        if new.event_type = any (decisions) and new.actor = t.requested_by then
            raise exception 'segregation of duties: % requested task % and cannot decide it', new.actor, t.id
                using errcode = 'check_violation';
        end if;
        if new.event_type = 'assigned' and new.assignee = t.requested_by then
            raise exception 'segregation of duties: approval task % cannot be assigned to its requester', t.id
                using errcode = 'check_violation';
        end if;
        if new.event_type = 'resubmitted' and new.actor <> t.requested_by then
            raise exception 'only the requester of task % resubmits it after rework', t.id
                using errcode = 'check_violation';
        end if;
    end if;
    return new;
end;
$$;

comment on function mesta.workflow_task_event_segregation() is
    'Enforces Task.next(): segregation of duties, kind-event fit, rework, time order, and nothing after a decision.';
