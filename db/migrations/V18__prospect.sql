-- V18__prospect.sql
-- Deal-sourcing prospect pipeline: the deterministic core the Investment Analyst and CRM AIP
-- workflows act on (issue #201, #6 slice 11+).
--
-- Grounded in:
--   V5__workflow_task.sql          the header + event-sourced state pattern this mirrors: a task's
--                                  status is never stored, only replayed from its events
--   V8__tenant_access.sql          event log as the access record; rationale required when access
--                                  is taken away — the same for a prospect that is passed on
--   #6 decision 2 (2026-09-25)     every new domain table carries tenant_id from its first migration
--   AGENTS.md                      deal-sourcing is T2: the AI slices build on this store, they do
--                                  not replace it
--
-- A prospect registers once (immutable header) and moves through
-- sourced → screening → due-diligence → ic-review → invested | passed. The stage is replayed from
-- prospect_event in Kotlin — no status column, no update path. `passed` and `invested` are terminal
-- and require a rationale: a pipeline that cannot say why it declined is an audit gap.
-- Numbered V18 after V10–V17; must merge after them or be renumbered.

create table mesta.prospect (
    id              uuid        primary key default gen_random_uuid(),
    tenant_id       uuid        not null references mesta.tenant (id),
    name            text        not null,
    source          text        not null,
    sector          text,
    region          text,
    description     text,
    registered_at   timestamptz not null,
    source_system   text        not null,
    actor           text        not null,
    correlation_id  uuid        not null,
    recorded_at     timestamptz not null default now(),

    constraint prospect_name_named check (length(btrim(name)) > 0),
    constraint prospect_source_known check (source in ('manual', 'crm', 'event', 'referral', 'inbound')),
    constraint prospect_actor_named check (length(btrim(actor)) > 0)
);

comment on table mesta.prospect is
    'Immutable prospect registration. Its pipeline stage is replayed from mesta.prospect_event.';
comment on column mesta.prospect.source is
    'Closed enumeration of intake channels; adding one is a migration.';

create table mesta.prospect_event (
    id              uuid        primary key default gen_random_uuid(),
    seq             bigint      generated always as identity,
    prospect_id     uuid        not null references mesta.prospect (id),
    event_type      text        not null,
    stage_from      text,
    stage_to        text,
    actor           text        not null,
    rationale       text,
    occurred_at     timestamptz not null,
    recorded_at     timestamptz not null default now(),
    correlation_id  uuid        not null,

    constraint prospect_event_type_known check (event_type in ('advanced', 'passed', 'invested')),
    constraint prospect_event_stages_known check (
        (stage_from is null or stage_from in ('sourced', 'screening', 'due-diligence', 'ic-review', 'invested', 'passed'))
        and stage_to in ('screening', 'due-diligence', 'ic-review', 'invested', 'passed')
    ),
    constraint prospect_event_direction check (
        (event_type = 'advanced' and stage_to in ('screening', 'due-diligence', 'ic-review'))
        or (event_type = 'passed' and stage_to = 'passed')
        or (event_type = 'invested' and stage_to = 'invested')
    ),
    constraint prospect_event_actor_named check (length(btrim(actor)) > 0),
    constraint prospect_event_rationale_required
        check (event_type = 'advanced' or length(btrim(coalesce(rationale, ''))) > 0)
);

comment on table mesta.prospect_event is
    'Append-only stage transitions. seq orders replay; occurred_at is the business clock.';
comment on column mesta.prospect_event.stage_from is
    'Null on the registration event a store inserts alongside the first row; the transition it claims is checked in the state machine.';

create index prospect_event_replay on mesta.prospect_event (prospect_id, seq);
create index prospect_tenant_list on mesta.prospect (tenant_id);

create trigger prospect_append_only
    before update or delete on mesta.prospect
    for each row
    execute function mesta.reject_mutation();

create trigger prospect_no_truncate
    before truncate on mesta.prospect
    for each statement
    execute function mesta.reject_mutation();

create trigger prospect_event_append_only
    before update or delete on mesta.prospect_event
    for each row
    execute function mesta.reject_mutation();

create trigger prospect_event_no_truncate
    before truncate on mesta.prospect_event
    for each statement
    execute function mesta.reject_mutation();

grant select, insert on mesta.prospect, mesta.prospect_event to "${runtime_role}";
