-- V1__init.sql
-- Core IBOR ledger for Mesta-Asset.
--
-- Grounded in:
--   ADR-0001                     IBOR is derived, never written; the ledger is append-only
--   ADR-0002                     domain schemas stay isolated from Supabase internal schemas
--   ADR-0003                     PostgreSQL owns the ledger of record; the graph lives in TypeDB
--   data-security-governance.md  "Every event records source, actor, ingestion run, effective time,
--                                 recorded time, currency, and correlation ID"
--   quantitative-methodology.md  dated cash flows, declared currency, reproducible formulas
--   ontology/mesta-investment.tql  ledger-event attributes and the flow-type @values
--
-- Deliberately not in this migration: row-level security (the organization/fund scoping model and
-- the runtime role names are not decided yet) and the reconciliation staging tables (they belong
-- with the ingestion adapters). Both are tracked as follow-ups rather than guessed here.

create schema if not exists mesta;

comment on schema mesta is
    'Mesta-Asset domain schema. Kept separate from Supabase internal schemas (ADR-0002).';

create table mesta.ledger_event (
    id               uuid           primary key default gen_random_uuid(),
    external_id      text,
    flow_type        text           not null,
    monetary_amount  numeric(38, 10) not null,
    currency_code    char(3)        not null,
    occurred_at      timestamptz    not null,
    recorded_at      timestamptz    not null default now(),
    supersedes_id    uuid           references mesta.ledger_event (id),
    rationale        text,
    source_system    text           not null,
    actor            text           not null,
    ingestion_run_id uuid           not null,
    correlation_id   uuid           not null,

    constraint ledger_event_flow_type_known check (flow_type in (
        'contribution',
        'distribution',
        'management-fee',
        'expense',
        'carried-interest',
        'recallable-distribution',
        'other-income'
    )),
    constraint ledger_event_currency_code_shape check (currency_code ~ '^[A-Z]{3}$'),
    constraint ledger_event_no_self_supersede
        check (supersedes_id is null or supersedes_id <> id),
    constraint ledger_event_rationale_required_when_superseding
        check (supersedes_id is null or length(btrim(coalesce(rationale, ''))) > 0)
);

comment on table mesta.ledger_event is
    'Append-only IBOR ledger. Corrections are new rows linked by supersedes_id; existing rows are never updated or deleted.';
comment on column mesta.ledger_event.occurred_at is
    'When the cash flow happened. Use real transaction dates, never period-end approximations (quantitative-methodology.md section 10.1).';
comment on column mesta.ledger_event.recorded_at is
    'When the platform recorded the event. Distinct from occurred_at.';
comment on column mesta.ledger_event.supersedes_id is
    'The event this row corrects. Mirrors the TypeQL supersedes relation; the original row stays.';
comment on column mesta.ledger_event.rationale is
    'Why the event exists or why it corrects another. Mandatory when supersedes_id is set.';

create index ledger_event_occurred_at_idx on mesta.ledger_event (occurred_at);
create index ledger_event_currency_occurred_idx on mesta.ledger_event (currency_code, occurred_at);
create index ledger_event_supersedes_idx on mesta.ledger_event (supersedes_id)
    where supersedes_id is not null;

-- Idempotent ingestion (data-security-governance.md: "Use idempotent ingestion and correlation IDs
-- so interrupted jobs can restart safely"): a replayed source record must not insert twice.
create unique index ledger_event_source_external_key
    on mesta.ledger_event (source_system, external_id)
    where external_id is not null;

-- Append-only enforcement. data-security-governance.md: "The IBOR ledger is append-only.
-- Corrections use reversing and replacement events; historical events are not overwritten."
create function mesta.ledger_event_reject_mutation() returns trigger
    language plpgsql
as $$
begin
    raise exception
        'mesta.ledger_event is append-only: % rejected. Record a correcting event instead.',
        lower(tg_op)
        using errcode = 'restrict_violation',
              hint = 'Insert a new row with supersedes_id pointing at the row you meant to change.';
end;
$$;

comment on function mesta.ledger_event_reject_mutation() is
    'Trigger function that blocks UPDATE and DELETE on the append-only ledger.';

create trigger ledger_event_append_only
    before update or delete on mesta.ledger_event
    for each row
    execute function mesta.ledger_event_reject_mutation();
