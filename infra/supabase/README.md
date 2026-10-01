# infra/supabase — vendored Supabase distribution

Do not hand-maintain a Supabase compose file. Vendor the official pinned release and layer `docker-compose.override.yml` on top.

## Setup

`vendor/` is the official `docker/` directory vendored at `self-hosted/v0.8.1` (commit `8c7a4d9`, recorded in `vendor/.supabase-version`). Refresh with a new tag + diff review + staging rehearsal:

```bash
git clone --depth 1 --branch self-hosted/vX.Y.Z https://github.com/supabase/supabase /tmp/supabase
```

then either copy `docker/` over `vendor/` and review the git diff, or run `vendor/update.sh` (three-way merge that preserves `.env` and your edits; conflicts get standard merge markers).

Record the tag in the Dokploy project description and in the release notes.

## Override applied

`docker-compose.override.yml` in this directory:

- Attaches only the API gateway (Kong/Envoy) to `dokploy-network` for public ingress.
- Keeps `db`, `studio`, `storage`, `auth`, `rest` on private networks — no host ports, no public domains.
- Adds per-service resource limits.
- Realtime and Edge Runtime remain removed from the base compose per ADR-0002 (deferred).

## Deployed vs vendored

The live OCTO Supabase on Dokploy is the **Dokploy Supabase template** (raw `composeFile` + generated env, Kong gateway, `octo-supabase-*` containers) — `update.sh` does not touch it. Updating the deployed service = bump image tags in the Dokploy composeFile after reviewing `vendor/CHANGELOG.md`, then redeploy. The vendored tree is the version-tracked reference of record for what an upstream release looks like.

## Env

Supabase env vars follow the official `.env.example` — configure values in Dokploy project env, sourced from the secret manager. Generate keys with the vendored `utils/generate-keys.sh` and `utils/add-new-auth-keys.sh` per environment.

### Solana sign-in (SIWS)

GoTrue enables SIWS from `GOTRUE_EXTERNAL_WEB3_SOLANA_ENABLED` on the `auth` container. A Dokploy env var only reaches a container when the composeFile references it, so setting that name in the env tab alone does nothing. To enable it on the deployed stack:

1. In the Dokploy composeFile, confirm the `auth` service `environment:` has `GOTRUE_EXTERNAL_WEB3_SOLANA_ENABLED: ${SOLANA_ENABLED}` (as in `vendor/docker-compose.yml`); add it if the template lacks it.
2. Set `SOLANA_ENABLED=true` in the project env. Unset interpolates to an empty string, which GoTrue reads as off.
3. Redeploy the Supabase project. `DISABLE_SIGNUP` still applies: a first wallet sign-in creates the account only when signup is open.
4. Verify. `/auth/v1/settings` does not report web3, so probe the token endpoint with a deliberately invalid payload (no account is created). `$ANON_KEY` is the public anon key the web app ships:

   ```bash
   curl -s -X POST -H "apikey: $ANON_KEY" -H 'Content-Type: application/json' \
     -d '{"chain":"solana","message":"probe","signature":"probe"}' \
     'https://supa-octo.mesta.click/auth/v1/token?grant_type=web3'
   ```

   `422 "error_code":"web3_provider_disabled"` means it is still off; `400 "error_code":"validation_failed"` (`message is too short`) means it is on. Then do one real sign-in from the web wallet button.

Rollback: set `SOLANA_ENABLED=false` and redeploy. Wallet-created accounts persist in `auth.users`.

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
