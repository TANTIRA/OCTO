-- V9__model_run.sql
-- Model-run store for state-space and regime models (issue #102, methodology §9.6, #6).
--
-- Grounded in:
--   quantitative-methodology.md §9.6  each model run stores family and immutable version, state definitions,
--                                     feature set and data vintage, windows, parameters, filtered probabilities
--                                     available at decision time, smoothed probabilities clearly labelled
--                                     historical-only, diagnostics, and benchmark or challenger status
--   quantitative-methodology.md §10.5 every metric definition is immutable after publication; corrections
--                                     create a new version
--   #6 decision 2 (2026-09-25)        every new domain table carries tenant_id from its first migration
--
-- Append-only like valuation_event: a run is never edited; a correction is a new version that supersedes it
-- with a rationale. Downstream decisions and overrides link through workflow_task.subject_type = 'model-run'.

create table mesta.model_run (
    id               uuid        primary key default gen_random_uuid(),
    tenant_id        uuid        not null references mesta.tenant (id),
    model_family     text        not null,
    model_version    text        not null,
    methodology      text        not null,
    state_definitions jsonb      not null default '{}'::jsonb,
    feature_set      jsonb       not null default '{}'::jsonb,
    frequency        text        not null,
    data_vintage     date        not null,
    training_start   date,
    training_end     date,
    validation_start date,
    validation_end   date,
    parameters       jsonb       not null,
    diagnostics      jsonb       not null default '{}'::jsonb,
    status           text        not null,
    supersedes_id    uuid        references mesta.model_run (id),
    rationale        text,
    actor            text        not null,
    correlation_id   uuid        not null,
    recorded_at      timestamptz not null default now(),

    constraint model_run_named check (
        length(btrim(model_family)) > 0 and length(btrim(model_version)) > 0
        and length(btrim(methodology)) > 0 and length(btrim(frequency)) > 0 and length(btrim(actor)) > 0),
    constraint model_run_status_known check (status in ('benchmark', 'challenger', 'retired')),
    constraint model_run_json_objects check (
        jsonb_typeof(state_definitions) = 'object' and jsonb_typeof(feature_set) = 'object'
        and jsonb_typeof(parameters) = 'object' and jsonb_typeof(diagnostics) = 'object'),
    constraint model_run_training_window check (
        (training_start is null) = (training_end is null) and training_start <= training_end),
    constraint model_run_validation_window check (
        (validation_start is null) = (validation_end is null) and validation_start <= validation_end),
    constraint model_run_no_self_supersede check (supersedes_id is null or supersedes_id <> id),
    constraint model_run_rationale_required_when_superseding
        check (supersedes_id is null or length(btrim(coalesce(rationale, ''))) > 0),
    -- §10.5: a version is immutable; re-running with other parameters is a new version.
    constraint model_run_version_unique unique (tenant_id, model_family, model_version)
);

comment on table mesta.model_run is
    'Append-only record of every model run (§9.6). Parameters and diagnostics are jsonb objects; a correction supersedes with a rationale under a new version.';

create table mesta.model_run_output (
    id         uuid  primary key default gen_random_uuid(),
    run_id     uuid  not null references mesta.model_run (id),
    as_of_date date  not null,
    kind       text  not null,
    values     jsonb not null,

    -- Smoothed output uses future observations (§9.1, §9.3): the label is what stops it being shown as live.
    constraint model_run_output_kind_known check (kind in ('filtered', 'smoothed')),
    constraint model_run_output_values_object check (jsonb_typeof(values) = 'object'),
    constraint model_run_output_one_per_date unique (run_id, as_of_date, kind)
);

comment on table mesta.model_run_output is
    'Per-date output of a run. kind = filtered is valid at decision time; kind = smoothed is historical-only.';

create trigger model_run_append_only
    before update or delete on mesta.model_run
    for each row
    execute function mesta.reject_mutation();

create trigger model_run_no_truncate
    before truncate on mesta.model_run
    for each statement
    execute function mesta.reject_mutation();

create trigger model_run_output_append_only
    before update or delete on mesta.model_run_output
    for each row
    execute function mesta.reject_mutation();

create trigger model_run_output_no_truncate
    before truncate on mesta.model_run_output
    for each statement
    execute function mesta.reject_mutation();

grant select, insert on mesta.model_run, mesta.model_run_output to "${runtime_role}";
