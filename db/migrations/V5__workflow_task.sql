-- V5__workflow_task.sql
-- Workflow tasks and their events (platform P1b, issue #50).
--
-- Grounded in:
--   docs/system-design.md          workflow state, approvals and audit live in PostgreSQL
--   data-security-governance.md    "No person may approve their own ... material data override ... or external
--                                   disclosure"; declines and overrides need a reason
--   deal-sourcing-workflow.md      IC decision: approve, decline, or request rework
--   modules/workflow/.../Task.kt   the state machine these rows replay through
--
-- A task's state is never stored: it is replayed from its events, like positions from the ledger, so both
-- tables are append-only. The database repeats the rules that must hold even when a caller bypasses the
-- Kotlin state machine: one terminal decision per task, and segregation of duties on approvals.

create table mesta.workflow_task (
    id              uuid        primary key default gen_random_uuid(),
    kind            text        not null,
    subject_type    text        not null,
    subject_id      text        not null,
    requested_by    text        not null,
    created_at      timestamptz not null default now(),
    source_system   text        not null,
    correlation_id  uuid        not null,

    constraint workflow_task_kind_known check (kind in ('approval', 'review', 'evidence-request')),
    constraint workflow_task_subject_named
        check (length(btrim(subject_type)) > 0 and length(btrim(subject_id)) > 0),
    constraint workflow_task_requester_named check (length(btrim(requested_by)) > 0)
);

comment on table mesta.workflow_task is
    'Immutable task header. Status, assignee and decision are replayed from mesta.workflow_task_event.';

create table mesta.workflow_task_event (
    id              uuid        primary key default gen_random_uuid(),
    task_id         uuid        not null references mesta.workflow_task (id),
    event_type      text        not null,
    actor           text        not null,
    assignee        text,
    rationale       text,
    occurred_at     timestamptz not null,
    recorded_at     timestamptz not null default now(),
    correlation_id  uuid        not null,

    constraint workflow_task_event_type_known check (event_type in (
        'assigned', 'approved', 'rejected', 'rework-requested', 'resubmitted', 'completed', 'cancelled'
    )),
    constraint workflow_task_event_actor_named check (length(btrim(actor)) > 0),
    constraint workflow_task_event_assignee_iff_assigned
        check ((event_type = 'assigned') = (assignee is not null and length(btrim(assignee)) > 0)),
    constraint workflow_task_event_rationale_required
        check (event_type not in ('rejected', 'rework-requested', 'cancelled')
               or length(btrim(coalesce(rationale, ''))) > 0)
);

comment on table mesta.workflow_task_event is
    'Append-only task history. A rejection, rework request or cancellation carries its rationale.';

create index workflow_task_event_task_idx on mesta.workflow_task_event (task_id, occurred_at);

-- A decided task stays decided, whatever the caller does.
create unique index workflow_task_event_one_terminal
    on mesta.workflow_task_event (task_id)
    where event_type in ('approved', 'rejected', 'completed', 'cancelled');

-- Segregation of duties on approval tasks. Runs as the inserting role, which already reads workflow_task.
create function mesta.workflow_task_event_segregation() returns trigger
    language plpgsql
as $$
declare
    t mesta.workflow_task;
begin
    select * into t from mesta.workflow_task where id = new.task_id;
    if t.kind = 'approval' then
        if new.event_type in ('approved', 'rejected', 'rework-requested') and new.actor = t.requested_by then
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

create trigger workflow_task_event_segregation
    before insert on mesta.workflow_task_event
    for each row
    execute function mesta.workflow_task_event_segregation();

create trigger workflow_task_append_only
    before update or delete on mesta.workflow_task
    for each row
    execute function mesta.reject_mutation();

create trigger workflow_task_event_append_only
    before update or delete on mesta.workflow_task_event
    for each row
    execute function mesta.reject_mutation();

grant select, insert on mesta.workflow_task, mesta.workflow_task_event to "${runtime_role}";
