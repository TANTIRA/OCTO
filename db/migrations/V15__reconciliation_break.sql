-- V15__reconciliation_break.sql
-- Reconciliation breaks between a source system and the IBOR (issue #107, #6 slice 9).
--
-- Grounded in:
--   decision-model-integration-map.md  break records live in PostgreSQL; break disposition is human
--   #6 decision 4 (2026-09-25)          PostgreSQL-only for now; the reconciliation-break ontology type follows
--   #6 decision 2 (2026-09-25)          every new domain table carries tenant_id from its first migration
--
-- A break is an append-only finding of one run. Its disposition is the decision on the evidence-request task
-- it opened, never a status column here. One open task per (kind, source, source_ref, ledger_event_id) across
-- runs: the partial unique index is the deduplication the runner relies on, so a break found again tomorrow
-- reuses yesterday's task.
-- Numbered V15 after V10–V14; must merge after them or be renumbered.

create table mesta.reconciliation_break (
    id              uuid        primary key default gen_random_uuid(),
    tenant_id       uuid        not null references mesta.tenant (id),
    run_id          uuid        not null,
    kind            text        not null,
    source_system   text        not null,
    source_ref      text,
    ledger_event_id uuid        references mesta.ledger_event (id),
    detail          jsonb       not null default '{}'::jsonb,
    task_id         uuid        references mesta.workflow_task (id),
    correlation_id  uuid        not null,
    recorded_at     timestamptz not null default now(),

    constraint reconciliation_break_kind_known check (kind in (
        'missing-in-ibor', 'missing-in-source', 'amount-mismatch', 'date-mismatch', 'currency-mismatch')),
    constraint reconciliation_break_source_named check (length(btrim(source_system)) > 0),
    constraint reconciliation_break_detail_object check (jsonb_typeof(detail) = 'object'),
    -- The side(s) a break has follow from its kind.
    constraint reconciliation_break_sides check (
        (kind = 'missing-in-ibor' and source_ref is not null and ledger_event_id is null)
        or (kind = 'missing-in-source' and ledger_event_id is not null)
        or (kind in ('amount-mismatch', 'date-mismatch', 'currency-mismatch') and source_ref is not null and ledger_event_id is not null))
);

comment on table mesta.reconciliation_break is
    'Append-only source-versus-IBOR findings. task_id is the evidence-request task reviewing the break; its decision is the disposition.';

create unique index reconciliation_break_one_task
    on mesta.reconciliation_break (tenant_id, kind, source_system, coalesce(source_ref, ''), coalesce(ledger_event_id, '00000000-0000-0000-0000-000000000000'::uuid))
    where task_id is not null;

create index reconciliation_break_run on mesta.reconciliation_break (run_id);

create trigger reconciliation_break_append_only
    before update or delete on mesta.reconciliation_break
    for each row
    execute function mesta.reject_mutation();

create trigger reconciliation_break_no_truncate
    before truncate on mesta.reconciliation_break
    for each statement
    execute function mesta.reject_mutation();

grant select, insert on mesta.reconciliation_break to "${runtime_role}";
