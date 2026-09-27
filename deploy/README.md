# deploy/ — Dokploy github-source stack

`dokploy.compose.yml` is the compose Dokploy runs for the OCTO **app** project
(`octo-app-kbf88q`). It builds `octo-api` and `octo-web` on the VPS from this
repo — no registry involved. Datastores are external services on
`dokploy-network` (`octo-supabase-db`, `octo-supabase-pooler`, `octo-neo4j-db`),
deployed by their own Dokploy projects; nothing here exposes public ports —
Traefik routes domains to labelled services.

## Source wiring

Dokploy clones from GitHub via its GitHub App, which can see only the
`daemon-blockint-tech` org — not `TANTIRA`, and org policy disables deploy
keys. Until the App is installed on `TANTIRA/OCTO`, production builds run off a
private mirror:

| Setting | Value |
| --- | --- |
| Source type | `github` |
| Repository | `daemon-blockint-tech/OCTO` (mirror of `TANTIRA/OCTO`) |
| Branch | `chore/octo-deploy` |
| Compose path | `./deploy/dokploy.compose.yml` |

Repoint to `TANTIRA/OCTO` once the GitHub App is installed on the org, then
retire the mirror.

## Environment model

Set in the Dokploy compose environment (never committed). Keys mirror
`infra/docker-compose.yml`; see `infra/.env.example` for the full contract.

- `POSTGRES_HOST` / `POSTGRES_PORT` / `POSTGRES_DB` → `DB_HOST`/`DB_PORT`/`DB_NAME` for the datasource.
- `DB_USER=octo_app`, `DB_MIGRATION_USER=octo_migrate` — the least-privilege roles from `infra/init-db-roles.sql` (create/rename before first api boot; `octo_migrate` needs `CREATE` on the database for the `mesta` schema + Flyway history).
- `SUPABASE_INTERNAL_URL`, `SUPABASE_STORAGE_ENDPOINT` → internal Kong URLs.
- `AUTH_ISSUER`, `AUTH_JWKS_URL`, `AUTH_PUBLIC_URL`, `API_PUBLIC_URL`.
- `NEO4J_URI` (default `bolt://octo-neo4j-db:7687`), `NEO4J_DATABASE`, `NEO4J_USER`, `NEO4J_PASSWORD`.
- Optional vendor keys (`HELIUS_*`, `ALPHA_VANTAGE_*`, `ARBITRUM_*`, `OPENROUTER_*`, `DECISION_MODEL*`) are declared as **bare pass-throughs** in the compose `environment:` list — they reach the container only when set in the Dokploy env. Do not give them empty defaults: Spring's `@ConditionalOnProperty` treats a present-but-empty value as *configured* and the api crash-loops (`rpcBaseUrl must be https`).

## Live domains

| Domain | Service | Notes |
| --- | --- | --- |
| `octo.mesta.click` | web | landing + `/app` shell + `/login` |
| `admin-octo.mesta.click` | web | `/` rewrites to `/admin` (ops surface) via `web/middleware.ts` |
| `api-octo.mesta.click` | api :8080 | `/actuator/health`, `/actuator/health/readiness` public |
| `supa-octo.mesta.click` | supabase kong :8000 | own compose project |
| `neo4j-octo.mesta.click` | neo4j :7474 | Browser only — bolt stays private |

## Gotchas (all learned the hard way)

- **Healthchecks and Traefik:** Dokploy drops *unhealthy* containers from the
  router — the domain 404s even while the server answers 200. The web check
  must hit `127.0.0.1`, not `localhost`: the Next standalone server binds IPv4
  `0.0.0.0`, and alpine resolves `localhost` to `::1` first.
- **`VPS_getProjectLogsV1` / `VPS_getProjectContainersV1`** (Hostinger VM
  `1943271`, project `octo-app-kbf88q`) are the fast path for crash loops and
  container health when Dokploy's own log procedures don't answer.
- **Redeploy vs deploy:** `compose.redeploy` reuses the built image; a compose
  or env change needs full `compose.deploy` (rebuild + recreate).
- **Env writes** go through `compose.update` with the complete env blob —
  round-trip via `compose.one` first and diff before writing.

## Verify after deploy

```bash
curl -sf https://api-octo.mesta.click/actuator/health           # {"status":"UP"}
curl -sf https://api-octo.mesta.click/actuator/health/readiness # includes db
curl -sf -o /dev/null -w '%{http_code}\n' https://octo.mesta.click/app
curl -sf -o /dev/null -w '%{http_code}\n' https://admin-octo.mesta.click
```

Flyway runs at api boot as `octo_migrate`; check
`mesta.flyway_schema_history` (`installed_by`) if a migration looks stale.

## Rollback

The compose is stateless — redeploy the previous branch commit from Dokploy.
The api is the only service that mutates state, exclusively via Flyway, and
migrations are forward-only; roll code forward, do not revert applied
migrations.
