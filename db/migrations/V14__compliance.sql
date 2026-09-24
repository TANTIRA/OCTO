-- V14__compliance.sql
-- Post-trade compliance: versioned rules and their evaluations (issue #106, #6 slice 8).
--
-- Grounded in:
--   AGENTS.md                    rules and model versions are versioned in Git, not only in a dashboard
--   #6 decision 2 (2026-09-25)   every new domain table carries tenant_id from its first migration
--   decision-model-integration-map.md  a breach opens a workflow task; the task's decision is the control
--
-- A rule version is immutable: a change is a new (rule_id, version) row. Evaluations are append-only facts;
-- a breach on one (rule, subject, date) is recorded once, so a re-run of the same day cannot open a second
-- task — the partial unique index is the deduplication the runner relies on.
-- Numbered V14 after V10–V13; must merge after them or be renumbered.

create table mesta.compliance_rule (
    id             uuid        primary key default gen_random_uuid(),
    tenant_id      uuid        not null references mesta.tenant (id),
    rule_id        text        not null,
    version        integer     not null,
    name           text        not null,
    definition     jsonb       not null,
    active         boolean     not null default true,
    actor          text        not null,
    correlation_id uuid        not null,
    recorded_at    timestamptz not null default now(),

    constraint compliance_rule_id_shape check (rule_id ~ '^[a-z0-9][a-z0-9._-]{0,63}$'),
    constraint compliance_rule_version_positive check (version >= 1),
    constraint compliance_rule_named check (length(btrim(name)) > 0 and length(btrim(actor)) > 0),
    constraint compliance_rule_definition_object check (jsonb_typeof(definition) = 'object' and definition ? 'check'),
    constraint compliance_rule_version_unique unique (tenant_id, rule_id, version)
);

comment on table mesta.compliance_rule is
    'Versioned compliance rules per tenant. definition is the ComplianceCheck as json; a change is a new version.';

create table mesta.compliance_evaluation (
    id             uuid        primary key default gen_random_uuid(),
    tenant_id      uuid        not null references mesta.tenant (id),
    rule_id        text        not null,
    rule_version   integer     not null,
    subject        text        not null,
    as_of_date     date        not null,
    result         text        not null,
    measured       jsonb       not null default '{}'::jsonb,
    explanation    text        not null,
    task_id        uuid        references mesta.workflow_task (id),
    correlation_id uuid        not null,
    recorded_at    timestamptz not null default now(),

    constraint compliance_evaluation_result_known check (result in ('pass', 'breach', 'not-evaluable')),
    constraint compliance_evaluation_named check (length(btrim(subject)) > 0 and length(btrim(explanation)) > 0),
    constraint compliance_evaluation_measured_object check (jsonb_typeof(measured) = 'object'),
    -- Only a breach opens a task.
    constraint compliance_evaluation_task_only_on_breach check (task_id is null or result = 'breach')
);

comment on table mesta.compliance_evaluation is
    'Append-only outcomes of rule evaluations. A breach carries the review task it opened.';

create unique index compliance_breach_once
    on mesta.compliance_evaluation (tenant_id, rule_id, subject, as_of_date)
    where result = 'breach';

create trigger compliance_rule_append_only
    before update or delete on mesta.compliance_rule
    for each row
    execute function mesta.reject_mutation();

create trigger compliance_rule_no_truncate
    before truncate on mesta.compliance_rule
    for each statement
    execute function mesta.reject_mutation();

create trigger compliance_evaluation_append_only
    before update or delete on mesta.compliance_evaluation
    for each row
    execute function mesta.reject_mutation();

create trigger compliance_evaluation_no_truncate
    before truncate on mesta.compliance_evaluation
    for each statement
    execute function mesta.reject_mutation();

grant select, insert on mesta.compliance_rule, mesta.compliance_evaluation to "${runtime_role}";
