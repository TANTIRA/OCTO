# infra/supabase — vendored Supabase distribution

Do not hand-maintain a Supabase compose file. Vendor the official pinned release and layer `docker-compose.override.yml` on top.

## Setup

```bash
# fetch the pinned release's docker/ directory
git clone --depth 1 --branch self-hosted/v0.8.1 https://github.com/supabase/supabase /tmp/supabase
cp -rf /tmp/supabase/docker/. ./vendor/          # reviewed, then committed or uploaded to Dokploy
```

Record the tag in the Dokploy project description and in the release notes. Upgrade = new tag + diff review + staging rehearsal.

## Override applied

`docker-compose.override.yml` in this directory:

- Attaches only the API gateway (Kong/Envoy) to `dokploy-network` for public ingress.
- Keeps `db`, `studio`, `storage`, `auth`, `rest` on private networks — no host ports, no public domains.
- Adds per-service resource limits.
- Realtime and Edge Runtime remain removed from the base compose per ADR-0002 (deferred).

## Env

Supabase env vars follow the official `.env.example` — configure values in Dokploy project env, sourced from the secret manager. Generate keys with the vendored `utils/generate-keys.sh` and `utils/add-new-auth-keys.sh` per environment.

## Local development

`local/` holds the Supabase CLI project (`project_id = "octo"`) for local development only — the CLI stack is not production-hardened (ADR-0002). Ports are shifted +1000 from CLI defaults (55321+) so it can run alongside other local stacks.

```bash
supabase --workdir infra/supabase/local start   # or stop
```

Endpoints: Postgres `localhost:55322`, Kong `localhost:55321`, Studio `localhost:55323`, Mailpit `localhost:55324`. App env values live in the repo-root `.env` (gitignored), which also points at the local Neo4j container:

```bash
docker start octo-neo4j-db   # neo4j:2025.12.1-community — bolt :7687, browser :7474
```

## Accessing Postgres (deployed)

Three modes per the official guide — all reachable on `dokploy-network` only (fixed `container_name`s, no host ports, nothing public):

| Mode | Endpoint | Use |
| --- | --- | --- |
| Direct | `postgresql://<user>:<pw>@octo-supabase-db:5432/postgres` | api (JDBC/HikariCP), Flyway migrations, `pg_dump` — long-lived backends |
| Session pooler | `postgresql://postgres.<POOLER_TENANT_ID>:<pw>@octo-supabase-pooler:5432/postgres` | persistent clients needing `SET`/`LISTEN`/`NOTIFY` |
| Transaction pooler | `postgresql://postgres.<POOLER_TENANT_ID>:<pw>@octo-supabase-pooler:6543/postgres` | short-lived/serverless clients — no prepared statements or session features |

- `supavisor` = `octo-supabase-pooler`; tenant ID lives in the Dokploy service env (`POOLER_TENANT_ID`).
- The api uses the direct connection — HikariCP is its own pool; pooler is for edge-style consumers.
- Local CLI stack: direct only at `localhost:55322` (local pooler disabled by default).
