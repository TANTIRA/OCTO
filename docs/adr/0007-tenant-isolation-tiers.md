# ADR-0007: Tenant Isolation Tiers (Pool, Bridge, Silo)

- Status: Proposed
- Date: 2026-09-28
- Risk tier: T2 (persistence, tenancy, infrastructure)
- Decision owner: CTO
- Depends on: [ADR-0001](0001-platform-architecture.md), [ADR-0002](0002-self-hosted-supabase.md)
- Related: V8 tenant access, V27 row-level security, V30–V32 tenancy migrations

## Context

OCTO is multi-tenant on one shared PostgreSQL schema: `tenant_id` columns + fail-closed RLS
(V27) + event-sourced membership (V8) + API-layer authorization. That is the **pool** model —
the cheapest per-tenant cost and the most scalable, and the right default for PE-scale tenancy.

But the tenancy megaplan names a gap: a regulated or sovereign tenant (an LP bound by
jurisdictional data residency, a defense-adjacent allocator) can require stronger isolation than
row-level predicates. The taxonomy in play:

| Tier | What is shared | Isolation | Cost |
| --- | --- | --- | --- |
| Pool | deployment + database + schema | row (`tenant_id` + RLS) | lowest |
| Bridge | deployment + application logic | separate database per tenant | medium |
| Silo | nothing — separate deployment + database | full | highest |

Tier-based isolation subsumes the others: every tenant holds a tier, and the deployment serves
them accordingly.

## Decision

**Adopt tier-based isolation with `pool` as the universal default, and ship the bridge plumbing
now without activating a non-pool tenant.**

1. **Registry.** `tenant.isolation_tier` (`pool` | `bridge` | `silo`, V32) + `tenant
   .datasource_key`, with a check making the key mandatory off-pool and forbidden on it.
   `TenantPlacements` (`JdbcAccessStore.placementOf`) is the runtime read surface.
2. **Routing seam.** `TenantRoutingDataSource` wraps the primary datasource as `@Primary`
   (`RoutingDataSourceConfiguration`). `TenantRoutingContext` binds a datasource key to the
   calling thread; unbound — every request today — resolves to the shared pool. Target
   connections come from `OCTO_TENANT_DATASOURCES` + `OCTO_DS_{KEY}_URL/_USER/_PASSWORD`; a
   listed key missing its triple fails startup.
3. **Fail posture.** An unregistered key degrades to the pool (lenient fallback), where RLS +
   tenant-scoped queries still constrain every row — never cross-tenant, never erroring open.
   Silo is deployment-level: such a tenant runs a separate stack; their row here is registry-only.
4. **Boundaries that do not move.** Membership, settings, and the access tables stay on the pool
   database — the access substrate must resolve before routing can pick a target. Domain data
   placement is what the tier governs.

Deliberately **not** shipped: per-request tenant→context binding (nothing resolves the work's
tenant at the filter layer yet — `X-Tenant-Id` is a rate-limit concern, not an authorization
source), tenant data migration between databases, and schema-per-tenant (rejected — N schema
copies to migrate beats no isolation problem we have).

## Consequences

### Positive

- A regulated tenant can be promoted to `bridge` with runbook steps only — no schema or code
  fork, no second deployment unless `silo` is demanded.
- Costs stay flat for everyone else: pool is unchanged and remains the default.
- The seam is exercised by ITs today, so the first real bridge tenant lands on tested plumbing.

### Negative

- Routing binds per thread: a mis-scoped `TenantRoutingContext.within` sends a whole block to
  one database — the runbook constrains its use to explicitly placement-aware code paths.
- Bridge tenants need their own Flyway + backup lifecycle (the runbook covers both).
- Membership still resolves on the pool: a bridge tenant's members read the pool to learn their
  placement — that is the design (the registry must live somewhere all tenants can be listed).

## Acceptance criteria

- [x] V32 columns + constraints applied; pool remains the sole populated tier.
- [x] Routing ITs: bound context → target DB; unbound/unknown key → pool; nested scopes restore.
- [ ] First bridge tenant promotion rehearsed against staging per
      `docs/tenant-isolation-runbook.md` before any regulated tenant commits.
