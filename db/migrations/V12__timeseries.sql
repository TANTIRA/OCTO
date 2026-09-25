-- V12__timeseries.sql
-- Bi-temporal time-series store behind the data service (issue #104, #6 slice 6, Marquee data service).
--
-- Grounded in:
--   docs/system-design.md row 6     query by dataset, date range, fields, asOfTime, since; bi-temporal, append-only
--   quantitative-methodology.md §10.8 backtest without leakage: point-in-time data
--   #6 decision 2 (2026-09-25)      every new domain table carries tenant_id from its first migration
--
-- Two times per observation: effective_date is when the value is true of the world, recorded_at is when
-- the platform learned it (clock_timestamp, so two rows in one transaction never collide). A correction is
-- a new row with a later recorded_at that supersedes the old one with a rationale; the value in force at
-- any asOfTime is the latest row recorded on or before it. A dataset must be registered before it is
-- written, and it belongs to one tenant.
-- Numbered V12 after V10 (#110) and V11 (#126); must merge after them or be renumbered.

create table mesta.dataset (
    id             uuid        primary key default gen_random_uuid(),
    tenant_id      uuid        not null references mesta.tenant (id),
    name           text        not null,
    description    text        not null,
    unit           text        not null,
    currency_code  char(3),
    source_system  text        not null,
    actor          text        not null,
    correlation_id uuid        not null,
    recorded_at    timestamptz not null default now(),

    constraint dataset_name_shape check (name ~ '^[a-z0-9][a-z0-9._-]{0,127}$'),
    constraint dataset_named check (
        length(btrim(description)) > 0 and length(btrim(unit)) > 0
        and length(btrim(source_system)) > 0 and length(btrim(actor)) > 0),
    constraint dataset_currency_code_shape check (currency_code is null or currency_code ~ '^[A-Z]{3}$'),
    constraint dataset_name_unique unique (tenant_id, name)
);

comment on table mesta.dataset is
    'Registry of time-series datasets, one tenant each. An observation can only be written into a registered dataset.';

create table mesta.timeseries_observation (
    id               uuid            primary key default gen_random_uuid(),
    dataset_id       uuid            not null references mesta.dataset (id),
    series_key       text            not null,
    field            text            not null,
    effective_date   date            not null,
    value            numeric(38, 10) not null,
    recorded_at      timestamptz     not null default clock_timestamp(),
    supersedes_id    uuid            references mesta.timeseries_observation (id),
    rationale        text,
    source_system    text            not null,
    actor            text            not null,
    ingestion_run_id uuid            not null,
    correlation_id   uuid            not null,

    constraint timeseries_observation_series_named check (length(btrim(series_key)) > 0),
    constraint timeseries_observation_field_shape check (field ~ '^[a-z][a-zA-Z0-9_]{0,63}$'),
    constraint timeseries_observation_provenance_named
        check (length(btrim(source_system)) > 0 and length(btrim(actor)) > 0),
    constraint timeseries_observation_no_self_supersede check (supersedes_id is null or supersedes_id <> id),
    constraint timeseries_observation_rationale_required_when_superseding
        check (supersedes_id is null or length(btrim(coalesce(rationale, ''))) > 0),
    constraint timeseries_observation_bitemporal_key unique (dataset_id, series_key, field, effective_date, recorded_at)
);

comment on table mesta.timeseries_observation is
    'Append-only bi-temporal observations. effective_date is valid time, recorded_at is transaction time; the latest recorded_at on or before asOfTime is the value in force.';

create index timeseries_observation_query
    on mesta.timeseries_observation (dataset_id, series_key, field, effective_date, recorded_at desc);

create trigger dataset_append_only
    before update or delete on mesta.dataset
    for each row
    execute function mesta.reject_mutation();

create trigger dataset_no_truncate
    before truncate on mesta.dataset
    for each statement
    execute function mesta.reject_mutation();

create trigger timeseries_observation_append_only
    before update or delete on mesta.timeseries_observation
    for each row
    execute function mesta.reject_mutation();

create trigger timeseries_observation_no_truncate
    before truncate on mesta.timeseries_observation
    for each statement
    execute function mesta.reject_mutation();

grant select, insert on mesta.dataset, mesta.timeseries_observation to "${runtime_role}";
