# OCTO — Deployment (Dokploy)

Deployment spec for the self-hosted stack. Two Dokploy compose projects share one external network. See [ADR-0002](../docs/adr/0002-self-hosted-supabase.md) for the decision and operational responsibilities, and [ADR-0003](../docs/adr/0003-typedb-ontology-store.md) for the graph-store decision — TypeDB there is superseded by Neo4j per #189 (the api now points at the external `octo-neo4j-db` service).

## Topology

```text
Internet
   │  HTTPS
   ▼
Dokploy Traefik (TLS, domains, request limits)
   ├── web          → octo project  (public)
   └── kong/envoy   → octo-supabase project (public API gateway only)

octo project            octo-supabase project       octo-neo4j project
┌─────────────────────┐        ┌──────────────────────────┐  ┌────────────────┐
│ web                 │        │ kong/envoy  (public)     │  │ neo4j          │
│ api ────────────────┼──data──┼─▶ db (postgres)          │  │  bolt (private)│
│        │            │        │  auth / rest / storage   │  │  7474 (public) │
│        └─bolt───────┼────────┼─dokploy-network──────────┼─▶│                │
└─────────────────────┘        │  studio (private only)   │  └────────────────┘
        private app net        └──────────────────────────┘
                                       private db net
```

- Only `web`, the Supabase gateway, and Neo4j Browser (7474) get public domains in Dokploy.
- `studio`, `db`, `storage`, `auth` internals and neo4j **bolt** are never publicly exposed — Studio is reached via VPN/bastion.
- `api` reaches Postgres/Storage over the shared `data` external network, and neo4j over `dokploy-network` (bolt `:7687`); clients never touch them.

## Projects

| Dokploy project | Compose | Purpose |
| --- | --- | --- |
| `octo-supabase-{env}` | official pinned Supabase `docker/` + `supabase/docker-compose.override.yml` | PostgreSQL, Auth, Storage, gateway |
| `octo-{env}` | `infra/docker-compose.yml` | api, web |
| `octo-neo4j-{env}` | own compose (outside this repo) | Neo4j graph store — bolt private, Browser public |

One project set per environment — self-hosted Supabase is single-project (ADR-0002).

## Build the api image

From the repo root, with the tag the environment's `API_IMAGE_TAG` expects:

```bash
docker build -t "$REGISTRY_URL/octo-api:$API_IMAGE_TAG" .
```

`Dockerfile` builds the boot jar with the repo's Gradle wrapper and runs it as a non-root user on a JRE with the heap sized to the container limit. `web` has its own image (`web/`).

## Deploy procedure

1. **Vendored Supabase:** copy the official `docker/` directory from the pinned `self-hosted/vX.Y.Z` release tag into the `octo-supabase-{env}` project; apply `supabase/docker-compose.override.yml`.
2. **Shared network:** create `supabase_default` (or set `DATA_NETWORK`) once; both projects attach it externally.
3. **Env:** configure project env vars in Dokploy from `infra/.env.example` — values come from the secret manager, never committed.
4. **Secrets:** generate fresh per environment (see Supabase `generate-keys.sh` / `add-new-auth-keys.sh`); prefer asymmetric JWT keys.
5. **Order:** `octo-supabase` first (db healthy) → `octo-neo4j` (bolt reachable on dokploy-network) → `octo` (`api` migrates via Flyway → `web`).
6. **Domains:** assign in Dokploy UI — `web` → app domain; gateway → api domain. TLS via Dokploy's Traefik.

## Backup (operator responsibility — no managed PITR)

- `db`: scheduled base backup + continuous WAL archive to deletion-protected storage.
- `neo4j-data` volume (on the octo-neo4j project): snapshot on schedule; graph is rebuildable from ledger + staging if lost.
- Storage objects: versioned backup/replication.
- Restore drills per ADR-0002 acceptance criteria.

## Guardrails

- Never commit `.env` or secrets — `.env.example` is names only.
- Images pinned by digest/tag; no `latest`.
- Changes to this directory are T2 — DevOps + Security review.
