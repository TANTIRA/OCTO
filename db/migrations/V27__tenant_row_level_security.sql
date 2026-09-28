-- V27__tenant_row_level_security.sql
-- Row-level security on every tenant-bearing table (#197).
--
-- Grounded in:
--   modules/persistence/TenantScope.kt   scoped() already mirrors TenantScope into the
--                                        transaction-local GUCs app.user_id / app.tenant_ids;
--                                        these policies are the DB-side enforcement those
--                                        GUCs were written for
--   ADR-0002                             RLS is defense in depth behind the API layer, not a
--                                        replacement for it — the edge still authenticates
--                                        and authorizes; this makes an omitted tenant_id
--                                        filter fail closed instead of leaking
--   V8__tenant_access.sql                membership is replayed from tenant_member_event;
--                                        rls_is_member applies the same rule (latest event's
--                                        role non-null = active)
--
-- Scope GUC contract (set by TenantScope.kt applyTenantScope, transaction-local):
--   app.tenant_ids = '*'      TenantScope.All   — platform scans bypass everything
--   app.tenant_ids = 'a,b,c'  TenantScope.Tenants — listed tenants only
--   app.user_id  = '<uuid>'   TenantScope.User  — tenants the user is an active member of
-- No GUCs set (a store call that forgot scoped()) → every predicate null → deny. Fail closed.
--
-- mesta.rls_is_member is SECURITY DEFINER so it can replay tenant_member_event while the
-- caller is subject to RLS — without it the policy on tenant_member_event would recurse into
-- itself. RLS is enabled, never FORCED: the owner (migration role) keeps DDL/ops access and
-- the definer functions still bypass when evaluating membership. The app runtime role is a
-- non-superuser (infra/init-db-roles.sql) so the policies bind it. Caveat: roles that own the
-- tables and superusers (the local/Testcontainers postgres login) bypass RLS by definition —
-- deployed envs use the least-privilege runtime role; the IT proves enforcement under one.
--
-- Deliberately not covered here:
--   workflow_task / workflow_task_event — no tenant_id by design; the API binds access to the
--       task's subject (prospect/report-job) — see ProspectController's subject binding
--   ledger_event, valuation_event, audit_event, instrument, instrument_flow,
--   document_classification, claim_assessment, decision_staging — predate V9's
--       "every new domain table carries tenant_id"; their entity-scoping migration is the
--       follow-up slice, and instrument/audit_event are platform-global anyway
--   onchain_transfer / onchain_balance_snapshot / onchain_claim_evidence — link to
--       tracked_address by address text, not FK; their scoping rides the same follow-up

-- Latest-membership lookup, mirrored from JdbcAccessStore's replay rule.
create or replace function mesta.rls_is_member(p_tenant_id uuid, p_user_id uuid)
    returns boolean
    language sql
    stable
    security definer
    set search_path = mesta, pg_temp
as $$
    select coalesce((
        select e.role is not null
        from mesta.tenant_member_event e
        where e.tenant_id = p_tenant_id
          and e.user_id = p_user_id
        order by e.seq desc
        limit 1
    ), false)
$$;

-- The single predicate every policy shares: All's '*' bypass, the Tenants list, or the
-- user's active membership. NULL tenant (platform-shared tracked_address) passes only
-- under '*'.
create or replace function mesta.rls_admits(p_tenant_id uuid)
    returns boolean
    language sql
    stable
    security definer
    set search_path = mesta, pg_temp
as $$
    select nullif(current_setting('app.tenant_ids', true), '') = '*'
        or p_tenant_id::text = any (string_to_array(nullif(current_setting('app.tenant_ids', true), ''), ','))
        or mesta.rls_is_member(p_tenant_id, nullif(current_setting('app.user_id', true), '')::uuid)
$$;

-- Tables carrying tenant_id directly.

alter table mesta.tenant enable row level security;
create policy tenant_scope on mesta.tenant
    using (mesta.rls_admits(id))
    with check (mesta.rls_admits(id));

alter table mesta.tenant_member enable row level security;
create policy tenant_scope on mesta.tenant_member
    using (mesta.rls_admits(tenant_id))
    with check (mesta.rls_admits(tenant_id));

alter table mesta.tenant_member_event enable row level security;
create policy tenant_scope on mesta.tenant_member_event
    using (mesta.rls_admits(tenant_id))
    with check (mesta.rls_admits(tenant_id));

alter table mesta.prospect enable row level security;
create policy tenant_scope on mesta.prospect
    using (mesta.rls_admits(tenant_id))
    with check (mesta.rls_admits(tenant_id));

alter table mesta.screening_rule enable row level security;
create policy tenant_scope on mesta.screening_rule
    using (mesta.rls_admits(tenant_id))
    with check (mesta.rls_admits(tenant_id));

alter table mesta.asset enable row level security;
create policy tenant_scope on mesta.asset
    using (mesta.rls_admits(tenant_id))
    with check (mesta.rls_admits(tenant_id));

alter table mesta.dataset enable row level security;
create policy tenant_scope on mesta.dataset
    using (mesta.rls_admits(tenant_id))
    with check (mesta.rls_admits(tenant_id));

alter table mesta.model_run enable row level security;
create policy tenant_scope on mesta.model_run
    using (mesta.rls_admits(tenant_id))
    with check (mesta.rls_admits(tenant_id));

alter table mesta.compliance_rule enable row level security;
create policy tenant_scope on mesta.compliance_rule
    using (mesta.rls_admits(tenant_id))
    with check (mesta.rls_admits(tenant_id));

alter table mesta.compliance_evaluation enable row level security;
create policy tenant_scope on mesta.compliance_evaluation
    using (mesta.rls_admits(tenant_id))
    with check (mesta.rls_admits(tenant_id));

alter table mesta.report_job enable row level security;
create policy tenant_scope on mesta.report_job
    using (mesta.rls_admits(tenant_id))
    with check (mesta.rls_admits(tenant_id));

alter table mesta.report_schedule enable row level security;
create policy tenant_scope on mesta.report_schedule
    using (mesta.rls_admits(tenant_id))
    with check (mesta.rls_admits(tenant_id));

alter table mesta.reconciliation_break enable row level security;
create policy tenant_scope on mesta.reconciliation_break
    using (mesta.rls_admits(tenant_id))
    with check (mesta.rls_admits(tenant_id));

alter table mesta.tracked_address enable row level security;
create policy tenant_scope on mesta.tracked_address
    using (mesta.rls_admits(tenant_id))
    with check (mesta.rls_admits(tenant_id));

-- Child rows inherit tenancy from their parent row; the FK guarantees the parent exists,
-- and a deleted/missing parent leaves NULL, which fails closed.

alter table mesta.prospect_event enable row level security;
create policy tenant_scope on mesta.prospect_event
    using (mesta.rls_admits((select p.tenant_id from mesta.prospect p where p.id = prospect_id)))
    with check (mesta.rls_admits((select p.tenant_id from mesta.prospect p where p.id = prospect_id)));

alter table mesta.model_run_output enable row level security;
create policy tenant_scope on mesta.model_run_output
    using (mesta.rls_admits((select r.tenant_id from mesta.model_run r where r.id = run_id)))
    with check (mesta.rls_admits((select r.tenant_id from mesta.model_run r where r.id = run_id)));

alter table mesta.asset_xref enable row level security;
create policy tenant_scope on mesta.asset_xref
    using (mesta.rls_admits((select a.tenant_id from mesta.asset a where a.id = asset_id)))
    with check (mesta.rls_admits((select a.tenant_id from mesta.asset a where a.id = asset_id)));

alter table mesta.timeseries_observation enable row level security;
create policy tenant_scope on mesta.timeseries_observation
    using (mesta.rls_admits((select d.tenant_id from mesta.dataset d where d.id = dataset_id)))
    with check (mesta.rls_admits((select d.tenant_id from mesta.dataset d where d.id = dataset_id)));

alter table mesta.tracked_address_event enable row level security;
create policy tenant_scope on mesta.tracked_address_event
    using (mesta.rls_admits((select ta.tenant_id from mesta.tracked_address ta
                            where ta.chain = chain and ta.address = address)))
    with check (mesta.rls_admits((select ta.tenant_id from mesta.tracked_address ta
                            where ta.chain = chain and ta.address = address)));

comment on function mesta.rls_is_member(uuid, uuid) is
    'Whether the user is an active member of the tenant: latest tenant_member_event carries a non-null role (V8 replay rule). Security definer so policies on the membership tables themselves cannot recurse.';
comment on function mesta.rls_admits(uuid) is
    'The tenant-scope predicate all RLS policies share. Reads the transaction-local GUCs TenantScope.kt sets; absent or empty GUCs make every branch false — an unscoped call fails closed.';
