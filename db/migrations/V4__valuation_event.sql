-- V4__valuation_event.sql
-- Append-only NAV / valuation facts for Mesta-Asset.
--
-- Grounded in:
--   ADR-0001                       IBOR is derived, never written; corrections are new rows
--   ADR-0003                       PostgreSQL owns financial facts; TypeDB owns relationships, so the
--                                  link to the valued subject lives in the valuation-of relation
--   issue #6 owner decision        NAV is stored in PostgreSQL (2026-09-24)
--   ontology/mesta-investment.tql  valuation-event attributes and the valuation-method @values
--
-- Same shape as mesta.ledger_event (V1): provenance on every row, idempotent replay key,
-- supersession with a mandatory rationale, and the append-only trigger from V2. Per V3 and
-- db/migrations/README.md, the runtime role gets select and insert only.

create table mesta.valuation_event (
    id               uuid            primary key default gen_random_uuid(),
    external_id      text,
    monetary_amount  numeric(38, 10) not null,
    currency_code    char(3)         not null,
    as_of_date       date            not null,
    valuation_method text,
    recorded_at      timestamptz     not null default now(),
    supersedes_id    uuid            references mesta.valuation_event (id),
    rationale        text,
    source_system    text            not null,
    actor            text            not null,
    ingestion_run_id uuid            not null,
    correlation_id   uuid            not null,

    constraint valuation_event_amount_not_negative check (monetary_amount >= 0),
    constraint valuation_event_currency_code_shape check (currency_code ~ '^[A-Z]{3}$'),
    constraint valuation_event_method_known check (valuation_method is null or valuation_method in (
        'dcf',
        'comparables',
        'lbo',
        'cost',
        'recent-round',
        'mark-to-model',
        'other'
    )),
    constraint valuation_event_no_self_supersede
        check (supersedes_id is null or supersedes_id <> id),
    constraint valuation_event_rationale_required_when_superseding
        check (supersedes_id is null or length(btrim(coalesce(rationale, ''))) > 0)
);

comment on table mesta.valuation_event is
    'Append-only valuation / NAV facts. The valued subject is attributed in TypeDB (valuation-of), not here (ADR-0003).';
comment on column mesta.valuation_event.as_of_date is
    'The date the valuation refers to. Distinct from recorded_at, which is when the platform learned it.';
comment on column mesta.valuation_event.supersedes_id is
    'The valuation this row corrects. Mirrors the TypeQL supersedes relation; the original row stays.';

create index valuation_event_as_of_date_idx on mesta.valuation_event (as_of_date);
create index valuation_event_supersedes_idx on mesta.valuation_event (supersedes_id)
    where supersedes_id is not null;

create unique index valuation_event_source_external_key
    on mesta.valuation_event (source_system, external_id)
    where external_id is not null;

create trigger valuation_event_append_only
    before update or delete on mesta.valuation_event
    for each row
    execute function mesta.reject_mutation();

grant select, insert on mesta.valuation_event to "${runtime_role}";
