-- V32__tenant_isolation_tier.sql
-- Isolation-tier metadata on the tenant boundary (tenancy megaplan slice E, ADR-0007).
--
-- Every tenant today is 'pool' — shared database, shared schema, row isolation via
-- tenant_id + RLS. A regulated or sovereign tenant can later be promoted:
--
--   pool    shared deployment, shared schema            (default, the cheapest tier)
--   bridge  shared deployment, own database/schema      (datasource_key names the target)
--   silo    separate deployment and database entirely   (key records the placement)
--
-- The row is the registry the routing seam (TenantRoutingDataSource) reads; the
-- biconditional check makes a key mandatory off-pool and forbidden on it, so a
-- tenant can never sit in a half-wired placement.

alter table octo.tenant
    add column isolation_tier text not null default 'pool',
    add column datasource_key text;

alter table octo.tenant
    add constraint tenant_isolation_tier_values
        check (isolation_tier in ('pool', 'bridge', 'silo')),
    add constraint tenant_datasource_key_shape
        check (datasource_key is null or datasource_key ~ '^[a-z0-9][a-z0-9_-]{0,62}$'),
    add constraint tenant_tier_datasource_key
        check ((isolation_tier = 'pool') = (datasource_key is null));

comment on column octo.tenant.isolation_tier is
    'pool | bridge | silo — the isolation model serving this tenant (ADR-0007). Membership and access rows always live on the shared pool database; the tier governs domain data placement.';
comment on column octo.tenant.datasource_key is
    'Placement key for non-pool tiers — resolved to a connection through OCTO_DS_<KEY>_URL/_USER/_PASSWORD. Must be null for pool tenants (registry invariant).';
