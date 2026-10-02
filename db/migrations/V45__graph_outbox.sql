-- V45__graph_outbox.sql
-- Transactional outbox for the Neo4j graph projection (ADR-0004 amendment, #308).
--
-- Postgres stays the ledger of record and the graph stays derived, so a graph write never joins a
-- ledger transaction. Instead the store call that writes a domain row inserts one row here on the
-- same connection: both commit or neither does. The api's GraphProjector claims due rows, applies
-- each with an idempotent MERGE keyed by aggregate_id (the node's octoId), and marks it applied.
--
-- Grounded in:
--   V27__tenant_row_level_security.sql   rls_admits policy, same shape as every tenant table
--   V33__agent_run.sql                   grant/comment conventions for a runtime-written table
--   V35__report_job_claim_lease.sql      claim_token / claimed_until lease, skip-locked claims

create table octo.graph_outbox (
    seq             bigint      generated always as identity,
    tenant_id       uuid        not null references octo.tenant (id),
    aggregate_type  text        not null,
    aggregate_id    uuid        not null,
    op              text        not null,
    payload         jsonb       not null,
    status          text        not null default 'pending',
    attempts        integer     not null default 0,
    next_attempt_at timestamptz not null default now(),
    claim_token     uuid,
    claimed_until   timestamptz,
    last_error      text,
    created_at      timestamptz not null default now(),
    applied_at      timestamptz,

    primary key (seq),
    constraint graph_outbox_aggregate_type_shape check (aggregate_type ~ '^[a-z][a-z0-9-]{0,62}$'),
    constraint graph_outbox_op check (op in ('upsert')),
    constraint graph_outbox_status check (status in ('pending', 'applied', 'failed')),
    constraint graph_outbox_attempts check (attempts >= 0),
    constraint graph_outbox_applied_at check ((status = 'applied') = (applied_at is not null)),
    constraint graph_outbox_claim_consistent check ((claim_token is null) = (claimed_until is null)),
    constraint graph_outbox_error_bounded check (length(last_error) <= 2000)
);

comment on table octo.graph_outbox is
    'Pending graph writes, enqueued in the same transaction as the domain row they project. Drained by the api GraphProjector into Neo4j.';
comment on column octo.graph_outbox.aggregate_id is
    'The projected node''s octoId: the domain row id, or for superseding rows the lineage-root id, so a correction updates the same node.';
comment on column octo.graph_outbox.payload is
    'Full node state for an upsert, e.g. {"kind": "fund", "properties": {"legalName": "Fund II LP"}}. Labels are derived from aggregate_type + kind by the projector, never read from here.';
comment on column octo.graph_outbox.status is
    'pending until applied; failed after the projector''s retry budget is spent — kept, counted and reported, never skipped.';
comment on column octo.graph_outbox.next_attempt_at is
    'Earliest time the projector may claim the row again; pushed out with exponential backoff after each failed attempt.';

-- Delete only prunes applied rows past retention; nothing else removes an outbox row.
grant select, insert, update, delete on octo.graph_outbox to "${runtime_role}";

alter table octo.graph_outbox enable row level security;
create policy tenant_scope on octo.graph_outbox
    using (octo.rls_admits(tenant_id))
    with check (octo.rls_admits(tenant_id));

create index graph_outbox_due on octo.graph_outbox (next_attempt_at, seq) where status = 'pending';
create index graph_outbox_aggregate on octo.graph_outbox (aggregate_id, seq);
create index graph_outbox_tenant on octo.graph_outbox (tenant_id, created_at);
