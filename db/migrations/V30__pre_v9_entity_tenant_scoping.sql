-- V30__pre_v9_entity_tenant_scoping.sql
-- Entity scoping for the pre-V9 tables (#197 follow-up, tenancy megaplan slice A).
--
-- Grounded in:
--   V27__tenant_row_level_security.sql  names this migration in its header: every table created
--                                       after V9 carries tenant_id; the pre-V9 families listed
--                                       there are the follow-up slice this file delivers
--   V8__tenant_access.sql               the access boundary these policies extend
--   ADR-0002                            RLS is defense in depth behind the API layer
--
-- Two scoping strategies, mirroring V27's two existing policy shapes:
--
--   Stamped tenant — ledger_event, valuation_event, document_classification, claim_assessment.
--   The subject these facts describe lives in the graph (ADR-0003), so no PG column derives the
--   tenant. Each row carries tenant_id stamped at write time; NULL means platform-shared and is
--   admitted only under TenantScope.All's '*'. Existing rows predate the column entirely — they
--   are backfilled to the house tenant 'octo-ops' (owner decision, megaplan) so tenant users keep
--   reading history. The backfill updates append-only tables: the migration role owns them, so
--   it disables each reject trigger, assigns the stamp, and re-enables — the same authority that
--   created the trigger, applied once under migration control, never at runtime.
--
--   Derived tenant — onchain_transfer, onchain_balance_snapshot, instrument_flow (chain+wallet),
--   onchain_claim_evidence (chain+subject_address). These are child rows of tracked_address, which
--   already carries tenant_id (V10): the policy subselects the parent's tenant exactly as V27 does
--   for tracked_address_event. A row on an untracked address resolves NULL → platform-shared under
--   '*', so untracked writes fail closed toward the platform, never toward another tenant.
--
-- Deliberately not scoped (V27's exclusions stand):
--   instrument          platform-global reference data (one SPL mint is the same asset for everyone)
--   audit_event         platform-global tamper-evident chain; the runtime role already cannot read it
--   workflow_task*      bound to their subject (prospect/report-job) at the API layer — V23 index
--   tracked_address*    already covered by V27
--
-- Contract for writers (enforced by invisibility, not by error): a row written with the wrong or
-- missing tenant stamp is not leaked to other tenants — it disappears from every scope but '*'.
-- Reader contracts: JdbcIborReader and JdbcDecisionStore must run inside DataSource.scoped() and
-- writers must stamp tenant_id — KDoc on both classes repeats this.

-- -----------------------------------------------------------------------------
-- House tenant for the backfill. Immutable like every tenant row: 'octo-ops' is the operating
-- firm's boundary for data that predates per-tenant stamping. Membership is granted through the
-- normal tenant_member_event path (or the admin provisioning API once it exists).
-- -----------------------------------------------------------------------------

insert into octo.tenant (slug, display_name, source_system, correlation_id)
values ('octo-ops', 'OCTO Operations', 'V30-backfill', gen_random_uuid())
on conflict (slug) do nothing;

-- -----------------------------------------------------------------------------
-- Stamped-tenant tables: column, backfill, RLS.
-- -----------------------------------------------------------------------------

alter table octo.ledger_event
    add column tenant_id uuid references octo.tenant (id);

alter table octo.valuation_event
    add column tenant_id uuid references octo.tenant (id);

alter table octo.document_classification
    add column tenant_id uuid references octo.tenant (id);

alter table octo.claim_assessment
    add column tenant_id uuid references octo.tenant (id);

comment on column octo.ledger_event.tenant_id is
    'The tenant boundary this cash-flow fact sits in (V30). NULL = platform-shared, admitted only under TenantScope.All. Stamped by the writer — no PG column derives it; the valued/flowed subject lives in the graph.';
comment on column octo.valuation_event.tenant_id is
    'The tenant boundary this valuation fact sits in (V30). Same stamped/NULL contract as ledger_event.tenant_id.';
comment on column octo.document_classification.tenant_id is
    'The tenant boundary this classifier fact sits in (V30). Same stamped/NULL contract as ledger_event.tenant_id.';
comment on column octo.claim_assessment.tenant_id is
    'The tenant boundary this support verdict sits in (V30). Same stamped/NULL contract as ledger_event.tenant_id.';

alter table octo.ledger_event disable trigger ledger_event_append_only;
update octo.ledger_event set tenant_id = (select id from octo.tenant where slug = 'octo-ops')
    where tenant_id is null;
alter table octo.ledger_event enable trigger ledger_event_append_only;

alter table octo.valuation_event disable trigger valuation_event_append_only;
update octo.valuation_event set tenant_id = (select id from octo.tenant where slug = 'octo-ops')
    where tenant_id is null;
alter table octo.valuation_event enable trigger valuation_event_append_only;

alter table octo.document_classification disable trigger document_classification_append_only;
update octo.document_classification set tenant_id = (select id from octo.tenant where slug = 'octo-ops')
    where tenant_id is null;
alter table octo.document_classification enable trigger document_classification_append_only;

alter table octo.claim_assessment disable trigger claim_assessment_append_only;
update octo.claim_assessment set tenant_id = (select id from octo.tenant where slug = 'octo-ops')
    where tenant_id is null;
alter table octo.claim_assessment enable trigger claim_assessment_append_only;

alter table octo.ledger_event enable row level security;
create policy tenant_scope on octo.ledger_event
    using (octo.rls_admits(tenant_id))
    with check (octo.rls_admits(tenant_id));

alter table octo.valuation_event enable row level security;
create policy tenant_scope on octo.valuation_event
    using (octo.rls_admits(tenant_id))
    with check (octo.rls_admits(tenant_id));

alter table octo.document_classification enable row level security;
create policy tenant_scope on octo.document_classification
    using (octo.rls_admits(tenant_id))
    with check (octo.rls_admits(tenant_id));

alter table octo.claim_assessment enable row level security;
create policy tenant_scope on octo.claim_assessment
    using (octo.rls_admits(tenant_id))
    with check (octo.rls_admits(tenant_id));

-- -----------------------------------------------------------------------------
-- Derived-tenant child rows: tenancy resolved through tracked_address, the V27 subselect shape.
-- A wallet/subject with no tracked_address row (or a platform-watch NULL tenant) resolves NULL
-- and is admitted under '*' only.
-- -----------------------------------------------------------------------------

alter table octo.onchain_transfer enable row level security;
create policy tenant_scope on octo.onchain_transfer
    using (octo.rls_admits((select ta.tenant_id from octo.tracked_address ta
                            where ta.chain = chain and ta.address = wallet)))
    with check (octo.rls_admits((select ta.tenant_id from octo.tracked_address ta
                                 where ta.chain = chain and ta.address = wallet)));

alter table octo.onchain_balance_snapshot enable row level security;
create policy tenant_scope on octo.onchain_balance_snapshot
    using (octo.rls_admits((select ta.tenant_id from octo.tracked_address ta
                            where ta.chain = chain and ta.address = wallet)))
    with check (octo.rls_admits((select ta.tenant_id from octo.tracked_address ta
                                 where ta.chain = chain and ta.address = wallet)));

alter table octo.instrument_flow enable row level security;
create policy tenant_scope on octo.instrument_flow
    using (octo.rls_admits((select ta.tenant_id from octo.tracked_address ta
                            where ta.chain = chain and ta.address = wallet)))
    with check (octo.rls_admits((select ta.tenant_id from octo.tracked_address ta
                                 where ta.chain = chain and ta.address = wallet)));

alter table octo.onchain_claim_evidence enable row level security;
create policy tenant_scope on octo.onchain_claim_evidence
    using (octo.rls_admits((select ta.tenant_id from octo.tracked_address ta
                            where ta.chain = chain and ta.address = subject_address)))
    with check (octo.rls_admits((select ta.tenant_id from octo.tracked_address ta
                                 where ta.chain = chain and ta.address = subject_address)));
