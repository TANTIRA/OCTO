-- V33__agent_run.sql
-- One auditable row per production agent-sidecar run (ADR-0005 / agent-layer megaplan F4).
-- Judging 100% of production runs needs the verdict, its lineage and its human outcome persisted
-- tenant-scoped — CI evals alone do not observe production drift, and the recalibration loop (F12)
-- reads exactly these columns.
--
-- Grounded in:
--   V8__tenant_access.sql                the boundary a run scopes to
--   V27__tenant_row_level_security.sql   rls_admits policy, same shape as every tenant table
--   V31__tenant_setting.sql              grant/comment conventions for a runtime-written table
--
-- The row itself is the audit record: a workflow run is an immutable-ish fact with two life
-- phases — `running` after record(), terminal (completed/failed/refused) after finish(). The
-- machine verdict lands in `verdict`; the human's eventual decision lands in `human_outcome`,
-- which is what F12 compares them on. No audit_event shadow — that would double-log the same fact.

create table octo.agent_run (
    id              uuid        not null default gen_random_uuid(),
    tenant_id       uuid        not null references octo.tenant (id),
    workflow        text        not null,
    run_key         text        not null,
    subject_type    text        not null,
    subject_id      text        not null,
    status          text        not null,
    actor           text        not null,
    input           jsonb       not null,
    output          jsonb,
    verdict         jsonb,
    models          jsonb       not null,
    thresholds      jsonb,
    request_ids     jsonb,
    error           text,
    human_outcome   jsonb,
    source_system   text        not null,
    correlation_id  uuid        not null,
    created_at      timestamptz not null default now(),
    finished_at     timestamptz,

    primary key (id),
    constraint agent_run_run_key unique (tenant_id, run_key),
    constraint agent_run_status check (status in ('running', 'completed', 'failed', 'refused')),
    constraint agent_run_workflow_shape check (workflow ~ '^[a-z][a-z0-9-]{0,62}$'),
    constraint agent_run_subject_type_shape check (subject_type ~ '^[a-z][a-z0-9_-]{0,62}$'),
    constraint agent_run_finished_at check (finished_at is null or status <> 'running')
);

comment on table octo.agent_run is
    'Auditable record of every production agent-sidecar workflow run: machine verdict + lineage + eventual human outcome, tenant-scoped.';
comment on column octo.agent_run.run_key is
    'Caller-supplied dedupe key — a retried trigger records one row per logical run; on conflict the caller reads the existing row back.';
comment on column octo.agent_run.input is
    'Request payload plus state lineage (e.g. prospect event ids/versions the run judged) — replay and debugging start here.';
comment on column octo.agent_run.verdict is
    'Typed jev answers as returned by the decisions API, e.g. {"advance": {"noul": 0.83}, "rationale": {"choice": "..."}, "quality": {"score": 4}}.';
comment on column octo.agent_run.models is
    'Model ids as registered in models.yaml, e.g. {"drafter": "deepseek/deepseek-v4.1-flash", "judge": "typesafe/jev-1.13"}.';
comment on column octo.agent_run.request_ids is
    'Provider request ids for the drafter and judge calls — the lineage the provider console reconciles against.';
comment on column octo.agent_run.human_outcome is
    'The human decision once known, e.g. {"decided_by": sub, "decision": "advanced", "decided_at": ts, "task_id": id} — F12 compares it against verdict.';
comment on column octo.agent_run.status is
    'running after record(); completed, failed or refused after finish(). A row stuck in running is a crashed run, not a pending decision.';

grant select, insert, update on octo.agent_run to "${runtime_role}";

alter table octo.agent_run enable row level security;
create policy tenant_scope on octo.agent_run
    using (octo.rls_admits(tenant_id))
    with check (octo.rls_admits(tenant_id));

create index agent_run_subject on octo.agent_run (tenant_id, subject_type, subject_id);
create index agent_run_workflow_recent on octo.agent_run (tenant_id, workflow, created_at desc);
