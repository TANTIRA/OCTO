-- #201: per-tenant screening criteria, versioned like mesta.compliance_rule (V14) — a rule change is a
-- new (tenant_id, rule_id, version) row, never an edit, so the criteria a verdict applied stay
-- auditable. The evaluator reads only the JSON document below; AI screening later must produce a
-- verdict shaped by the same criteria model, not a free-text opinion (deterministic core first).

create table mesta.screening_rule (
    id             uuid        primary key default gen_random_uuid(),
    tenant_id      uuid        not null references mesta.tenant (id),
    rule_id        text        not null,
    version        integer     not null,
    name           text        not null,
    criteria       jsonb       not null,
    active         boolean     not null default true,
    actor          text        not null,
    correlation_id uuid        not null,
    recorded_at    timestamptz not null default now(),

    constraint screening_rule_id_shape check (rule_id ~ '^[a-z0-9][a-z0-9._-]{0,63}$'),
    constraint screening_rule_version_positive check (version >= 1),
    constraint screening_rule_named check (length(btrim(name)) > 0 and length(btrim(actor)) > 0),
    constraint screening_rule_criteria_object check (jsonb_typeof(criteria) = 'object'),
    constraint screening_rule_version_unique unique (tenant_id, rule_id, version)
);

comment on table mesta.screening_rule is
    'Versioned per-tenant deal-screening criteria. The latest active version governs; older versions
     stay so a past verdict can be attributed to the exact criteria that produced it.';

create trigger screening_rule_no_update before update on mesta.screening_rule
    for each row execute function mesta.reject_mutation();
create trigger screening_rule_no_delete before delete on mesta.screening_rule
    for each row execute function mesta.reject_mutation();
create trigger screening_rule_no_truncate before truncate on mesta.screening_rule
    for each statement execute function mesta.reject_mutation();

grant select, insert on mesta.screening_rule to "${runtime_role}";
