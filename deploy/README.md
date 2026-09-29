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
- `DB_USER=octo_app`, `DB_MIGRATION_USER=octo_migrate` — the least-privilege roles from `infra/init-db-roles.sql` (create/rename before first api boot; `octo_migrate` needs `CREATE` on the database for the `octo` schema + Flyway history).
- `SUPABASE_INTERNAL_URL`, `SUPABASE_STORAGE_ENDPOINT` → internal Kong URLs.
- `AUTH_ISSUER`, `AUTH_JWKS_URL`, `AUTH_PUBLIC_URL`, `API_PUBLIC_URL`.
  `AUTH_ISSUER` stays the *public* issuer string (it is matched against the
  token `iss` claim), but `AUTH_JWKS_URL` must be the **internal** Kong path —
  `http://octo-supabase-tonh7d-kong-1:8000/auth/v1/.well-known/jwks.json`. The
  public URL resolves to the Traefik ingress IP and the api container cannot
  hairpin back through it, so JWKS fetches fail and every bearer token 401s.
- `NEXT_PUBLIC_SUPABASE_URL`, `NEXT_PUBLIC_SUPABASE_ANON_KEY` — publishable
  anon key, baked into the web bundle by the Dockerfile build args.
- `API_INTERNAL_URL` — `/api/*` same-origin proxy target. **Also a build
  arg**: Next.js evaluates `rewrites()` during `next build` and serializes
  the result into the standalone routes manifest, so a runtime env var can
  never reach it. Hardcoded `http://api:8080` in the compose `build.args`
  (compose-network service name, not a secret).
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

## Neo4j Browser access

The public Browser at `neo4j-octo.mesta.click` is served over HTTPS, so it only
accepts encrypted connections (`bolt+s`/`neo4j+s`) — and bolt itself is private
to `dokploy-network` with TLS disabled, so no remote connection can ever
complete. To browse the deployed graph, tunnel bolt and use an HTTP-hosted
Browser:

```bash
# Dokploy host — expose bolt on loopback only, once:
docker run -d --name octo-bolt-bridge --network dokploy-network \
  -p 127.0.0.1:7687:7687 --restart unless-stopped \
  alpine/socat TCP-LISTEN:7687,fork,reuseaddr TCP:octo-neo4j-db:7687

# Local — tunnel, then any HTTP Browser (e.g. a local neo4j's own):
ssh -N -L 7687:localhost:7687 <dokploy-host>
docker run -d --name octo-browser -p 7474:7474 neo4j:2025.12.1-community
# http://localhost:7474 → bolt://localhost:7687
```

If remote HTTPS browsing becomes a real requirement, exposing bolt through a
TLS-terminating Traefik TCP router is an ADR-level decision — it puts bolt on
the public internet behind only neo4j auth, contra the privacy posture above.

## Rate limiting (Traefik, via Dokploy)

No application-level rate limiting exists by design — the proxy owns it.
Dokploy's Traefik accepts per-router middlewares on each domain entry; the
middleware itself is declared once as a file-provider dynamic config on the
Dokploy host (default: `/etc/dokploy/traefik/dynamic/`):

```yaml
# /etc/dokploy/traefik/dynamic/octo-rate-limit.yml
http:
  middlewares:
    octo-ratelimit:
      rateLimit:
        average: 100        # requests/second sustained, per source IP
        period: 1s
        burst: 200          # short spikes above average
        sourceCriterion:
          ipStrategy:
            depth: 1        # X-Forwarded-For leftmost — Traefik fronts the api
```

Attach it in Dokploy → project → **Domains** → each domain's middleware field:
`octo-ratelimit@file`. Apply to `api-octo.mesta.click` first (the
unauthenticated attack surface); `octo.mesta.click`/`admin-octo.mesta.click`
can share the same middleware. The webhook route gets the same limit — its
shared-secret check is cheap, and bursts there are also just retries.

Tune `average`/`burst` against real traffic once Prometheus scrapes
`http.server.requests`; start conservative, watch for false 429s on import
batches (`IMPORT_BATCH_LIMIT`-sized bursts are legitimate).

## Gotchas (all learned the hard way)

- **Healthchecks and Traefik:** Dokploy drops *unhealthy* containers from the
  router — the domain 404s even while the server answers 200. The web check
  must hit `127.0.0.1`, not `localhost`: the Next standalone server binds IPv4
  `0.0.0.0`, and alpine resolves `localhost` to `::1` first.
- **`VPS_getProjectLogsV1` / `VPS_getProjectContainersV1`** (Hostinger VM
  `1943271`, project `octo-app-kbf88q`) are the fast path for crash loops and
  container health when Dokploy's own log procedures don't answer.
- **ES256 vs HS256 signing:** GoTrue signed HS256 until `JWT_KEYS`/`JWT_JWKS`
  were provisioned (dokploy helpers can't make EC keys — run
  `utils/add-new-auth-keys.sh` from supabase/supabase with the deployment's
  `JWT_SECRET`). Until then `/auth/v1/.well-known/jwks.json` returns
  `{"keys":[]}` and the api's JWKS decoder rejects every token.
- **Env changes don't recreate containers:** `docker compose up -d` only
  recreates services whose rendered config changed. A Supabase-side key/env
  change leaves the api running with a stale JWKS cache — restart the app
  project (`VPS_restartProjectV1`) after rotating signing material.
- **User provisioning is invite-only:** `DISABLE_SIGNUP=true`; operators add
  users via Supabase Studio → Authentication → Users (dashboard basic-auth in
  the supabase compose env). `octo-ops-smoke@test.invalid` is a synthetic
  smoke account for end-to-end auth checks, not a person.
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
`octo.flyway_schema_history` (`installed_by`) if a migration looks stale.

## Rollback

The compose is stateless — redeploy the previous branch commit from Dokploy.
The api is the only service that mutates state, exclusively via Flyway, and
migrations are forward-only; roll code forward, do not revert applied
migrations.
