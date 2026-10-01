-- V43__pin_octo_trigger_and_rls_bodies.sql
-- Pin the remaining functions whose stored bodies may still name the mesta schema.
--
-- V40 did this for tenant_member_event_rules(); the same split applies to four more:
--   * Fresh databases: V28 recreated every function with 'octo.' rewritten to 'octo.' —
--     this file restates those bodies and is a no-op in effect.
--   * Renamed databases: ops ran `alter schema mesta rename to octo`, V28's loop skipped, and
--     the stored bodies still name octo.* tables, types and lock keys. Every workflow task
--     event and audit write then fails ("octo.workflow_task does not exist"), the RLS
--     predicate fails closed, and the workflow lock key no longer matches JdbcTaskStore's.
-- Recreating from one pinned body converges both. Each body is its source migration's
-- verbatim with the qualifier changed (V7, V6, V27); the OIDs are kept, so triggers,
-- policies and comments carry over. create or replace resets proconfig, so each
-- search_path is set inline (V37 for the workflow trigger; V6/V27 as originally declared,
-- with mesta -> octo).

create or replace function octo.workflow_task_event_segregation() returns trigger
    language plpgsql
    set search_path = pg_catalog, pg_temp
as $$
declare
    t         octo.workflow_task;
    decisions constant text[] := array['approved', 'rejected', 'rework-requested'];
    terminal  constant text[] := array['approved', 'rejected', 'completed', 'cancelled'];
    latest_at timestamptz;
    in_rework boolean;
begin
    -- One writer per task at a time. JdbcTaskStore takes the same lock before it replays and appends.
    perform pg_advisory_xact_lock(hashtextextended('octo.workflow_task:' || new.task_id::text, 0));
    select * into t from octo.workflow_task where id = new.task_id;

    -- A second terminal event is V5's unique index's to refuse; anything else after a decision is refused here.
    if not (new.event_type = any (terminal)) and exists (
        select 1 from octo.workflow_task_event e where e.task_id = new.task_id and e.event_type = any (terminal)
    ) then
        raise exception 'task % is already decided', t.id using errcode = 'check_violation';
    end if;

    select max(e.occurred_at) into latest_at from octo.workflow_task_event e where e.task_id = new.task_id;
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
    from octo.workflow_task_event e
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

create or replace function octo.audit_event_chain() returns trigger
    language plpgsql
    security definer
    set search_path = pg_catalog, pg_temp
as $$
declare
    head_seq  bigint;
    head_hash bytea;
begin
    -- ponytail: one lock serializes every audit write; move to per-stream chains if audit volume needs parallel writers.
    perform pg_advisory_xact_lock(hashtextextended('octo.audit_event', 0));
    select e.seq, e.hash into head_seq, head_hash from octo.audit_event e order by e.seq desc limit 1;
    new.seq := coalesce(head_seq, 0) + 1;
    new.prev_hash := coalesce(head_hash, decode(repeat('00', 32), 'hex'));
    new.recorded_at := clock_timestamp();
    new.hash := sha256(new.prev_hash || octo.audit_event_canonical(
        new.seq, new.occurred_at, new.recorded_at, new.actor, new.action,
        new.subject_type, new.subject_id, new.correlation_id, new.details));
    return new;
end;
$$;

create or replace function octo.rls_is_member(p_tenant_id uuid, p_user_id uuid)
    returns boolean
    language sql
    stable
    security definer
    set search_path = octo, pg_temp
as $$
    select coalesce((
        select e.role is not null
        from octo.tenant_member_event e
        where e.tenant_id = p_tenant_id
          and e.user_id = p_user_id
        order by e.seq desc
        limit 1
    ), false)
$$;

create or replace function octo.rls_admits(p_tenant_id uuid)
    returns boolean
    language sql
    stable
    security definer
    set search_path = octo, pg_temp
as $$
    select nullif(current_setting('app.tenant_ids', true), '') = '*'
        or p_tenant_id::text = any (string_to_array(nullif(current_setting('app.tenant_ids', true), ''), ','))
        or octo.rls_is_member(p_tenant_id, nullif(current_setting('app.user_id', true), '')::uuid)
$$;
