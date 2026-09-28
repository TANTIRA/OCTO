# Tenant Isolation Runbook — promoting a tenant off the pool

ADR-0007 defines the tiers (`tenant.isolation_tier`: `pool` | `bridge` | `silo`). Every tenant
starts `pool`. This runbook promotes one to `bridge`: shared deployment, dedicated database.

## 0. Decision check

Bridge buys database-level isolation inside this deployment. If the tenant needs a separate
application instance, network boundary, or jurisdiction-pinned infra, that is `silo` — a
separate deployment of this same stack, not this runbook.

## 1. Provision the placement database

Stand up a PostgreSQL database for the tenant (same server is fine for bridge; a separate
cluster for residency requirements). Record it under a lowercase key — e.g. `bridge-acme` —
matching `^[a-z0-9][a-z0-9_-]{0,62}$`.

Create a least-privilege role for that database, mirroring the pool's runtime/migration split:

```sql
create role octo_bridge_app login password '…';
create role octo_bridge_migrator login password '…';
-- grants mirror infra/init-db-roles.sql, scoped to the placement database
```

## 2. Apply the schema

The placement database runs the same `db/migrations` as the pool:

```bash
flyway -url=jdbc:postgresql://<host>/<db> -user=octo_bridge_migrator \
       -schemas=octo -placeholders.runtime_role=octo_bridge_app migrate
```

The bridge database needs its own backup schedule and its own entry in the restore runbook
(`docs/restore-runbook.md`) before a tenant's data lands on it.

## 3. Register the connection

In the api's environment (`.env`, Dokploy service env):

```bash
OCTO_TENANT_DATASOURCES=bridge-acme            # comma-separated list of keys
OCTO_DS_BRIDGE_ACME_URL=jdbc:postgresql://<host>:5432/<db>
OCTO_DS_BRIDGE_ACME_USER=octo_bridge_app
OCTO_DS_BRIDGE_ACME_PASSWORD=…
OCTO_DS_BRIDGE_ACME_POOL_SIZE=5                # optional, default 5
```

A listed key missing any triple fails startup — the deployment never boots half-wired.

## 4. Flip the tier

On the pool database:

```sql
update octo.tenant
   set isolation_tier = 'bridge', datasource_key = 'bridge-acme'
 where id = '<tenant uuid>';
```

The registry check (`tenant_tier_datasource_key`) refuses a non-pool tier without a key and a
pool tier with one — the update is the promotion.

## 5. Route the tenant's work

`TenantRoutingContext.within("bridge-acme") { … }` binds the calling thread to the placement;
every `DataSource.getConnection()` inside the block lands on the bridge database. This is the
seam today — wrap it only in code paths that already know they are serving that tenant (the
binding must never come from a request header; derive it from the resolved tenant like
`TenantPlacements.placementOf` does).

Membership, settings, and the access tables stay on the pool — the directory must resolve
before routing picks a target, so the access substrate never moves off-pool.

## 6. Verify

- `tenant.isolation_tier = 'bridge'`, `datasource_key` set (step 4).
- A routed block reads/writes the placement database only — `TenantRoutingDataSourceIT`
  exercises the same path against two databases.
- Pool RLS still applies inside routed work (`scoped()` sets the same GUCs on the routed
  connection), so bridge isolation is additive, not a replacement.

## Rollback

```sql
update octo.tenant set isolation_tier = 'pool', datasource_key = null where id = '<tenant uuid>';
```

Then remove the key from `OCTO_TENANT_DATASOURCES` (and the database itself only after its
data is exported or the retention policy says it may go).
