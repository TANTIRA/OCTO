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

- `API_IMAGE_TAG`, `AGENTS_IMAGE_TAG`, `WEB_IMAGE_TAG` — **required, no default**. Each service's
  locally built image is tagged with these; a `:latest` fallback made the image a deploy actually
  ran impossible to name from the config, and unliftable into a rollback (#322). Use the commit sha
  of the deploy (CI's `type=sha` form, e.g. `main-cf5c269`) so the running image names its build.
- `POSTGRES_HOST` / `POSTGRES_DB` → `DB_HOST`/`DB_NAME` for the datasource. `DB_PORT` is hardcoded `5432` — the Postgres peer is on the compose network, so the port never varies; no `POSTGRES_PORT` key exists.
- `DB_USER=octo_app`, `DB_MIGRATION_USER=octo_migrate` — the least-privilege roles from `infra/init-db-roles.sql` (create/rename before first api boot; `octo_migrate` needs `CREATE` on the database for the `octo` schema + Flyway history).
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
- No `SUPABASE_*` or `NEO4J_*` on the api: nothing reads them (#340). The graph
  writer (ADR-0004, #308) reintroduces the `NEO4J_*` variables it reads.
- Optional vendor keys (`HELIUS_*`, `ALPHA_VANTAGE_*`, `ARBITRUM_*`, `OPENROUTER_*`, `DECISION_MODEL*`) are declared as **bare pass-throughs** in the compose `environment:` list — they reach the container only when set in the Dokploy env. Do not give them empty defaults: Spring's `@ConditionalOnProperty` treats a present-but-empty value as *configured* and the api crash-loops (`rpcBaseUrl must be https`).

## Live domains

| Domain | Service | Notes |
| --- | --- | --- |
| `octo.mesta.click` | web | landing + `/app` shell + `/login` |
| `admin-octo.mesta.click` | web | `/` rewrites to `/admin` (ops surface) via `web/middleware.ts` |
| `api-octo.mesta.click` | api :8080 | `/actuator/health`, `/actuator/health/readiness` public |
| `supa-octo.mesta.click` | supabase kong :8000 | own compose project |

Neo4j has no public domain (#390). Port 7474 serves the Browser *and* Neo4j's HTTP query API, so a
public route there is a password-only Cypher endpoint, not a viewer. Do not assign one; like Studio,
the graph store is reached only from the host.

## Neo4j Browser access

Bolt is private to `dokploy-network` with TLS disabled, and 7474 has no public route. To browse
the deployed graph, tunnel bolt and use a locally hosted Browser:

```bash
# Dokploy host — expose bolt on loopback only, once:
docker run -d --name octo-bolt-bridge --network dokploy-network \
  -p 127.0.0.1:7687:7687 --restart unless-stopped \
  alpine/socat TCP-LISTEN:7687,fork,reuseaddr TCP:octo-neo4j-db:7687

# Local — tunnel, then any HTTP Browser (e.g. a local neo4j's own):
ssh -N -L 7687:localhost:7687 <dokploy-host>
docker run -d --name octo-browser -p 7474:7474 neo4j:2025.12.1-community@sha256:c64d8750884c95ae57441a103d64d08fdaf55265acc3af687aa8ec25aa77d0c3
# http://localhost:7474 → bolt://localhost:7687
```

The tunnel is the settled path, not a workaround: [ADR-0006](../docs/adr/0006-neo4j-bolt-exposure.md)
records the decision to keep bolt private. Exposing bolt through a TLS-terminating Traefik TCP
router is re-opened only when the graph writer (#308) has a consumer outside the `api` — a
scheduled job, CI, or standing second-engineer access. Until then the bridge stays bound to
loopback and no bolt entryPoint is configured on the host.

## Rate limiting

The api enforces its own limits; the Traefik middleware below is defense in
depth, not a precondition (#321). In-app, always on:

- **Per tenant** (`RateLimitFilter`): authenticated JWT traffic,
  `OCTO_RATE_LIMIT_PER_MINUTE` (default 120/min, per-tenant override via the
  `rate_limit_per_minute` tenant setting).
- **Per client IP** (`ClientIpRateLimitFilter`, ahead of authentication on the
  JWT and webhook chains): 30 failed authentications/min (401, or 403 for an
  unauthenticated caller) before every request from that IP answers 429, and
  10/min on the anonymous `POST /api/v1/contact`. The IP is the rightmost
  `X-Forwarded-For` entry — the peer Traefik appended, not a client-chosen value.

Counters live in Redis when `REDIS_HOST` is set (the `redis` service below), so
replicas share one quota. Without Redis, or while it is failing, the api keeps
enforcing with in-process counters — per instance, never fail-open — and logs
one warning per 30s of outage.

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
            depth: 1        # X-Forwarded-For rightmost — the peer Traefik appended
```

Attach it in Dokploy → project → **Domains** → each domain's middleware field:
`octo-ratelimit@file`. Apply to `api-octo.mesta.click` first (the
unauthenticated attack surface); `octo.mesta.click`/`admin-octo.mesta.click`
can share the same middleware. The webhook route gets the same limit — its
shared-secret check is cheap, and bursts there are also just retries.

Tune `average`/`burst` against the `http_server_requests_seconds` metrics the
collector scrapes (below); start conservative, watch for false 429s on import
batches (`IMPORT_BATCH_LIMIT`-sized bursts are legitimate).

## Metrics scrape port (#338)

On the public port (`8080`, the only port Traefik routes) actuator is unchanged:
`/actuator/health/**` and `/actuator/info` are anonymous, `/actuator/metrics` and
`/actuator/prometheus` need a bearer JWT. A scraper has no user, so the api opens
a second connector on `OCTO_METRICS_PORT` (default `8081`) that answers anonymous
`GET /actuator/prometheus` and denies every other path (`MetricsPortConfig`).

- Never publish `8081` (`ports:`) or give it a Dokploy domain — it has no auth by
  design. Only containers on the stack's networks reach it.
- The request is matched by the socket's local port, not by a header, so traffic
  arriving through Traefik on `8080` cannot claim to be a scrape.
- Check from the host: `docker exec <api-container> curl -fsS localhost:8081/actuator/prometheus | head`.

## OTEL collector (#306)

`otel-collector` runs the pinned core `otel/opentelemetry-collector` image with the config in
`deploy/otel/` (bind-mounted from the cloned repo). It scrapes `api:8081/actuator/prometheus` every
30s and receives OTLP on `:4317`/`:4318` — the api sends traces there through
`MANAGEMENT_OTLP_TRACING_ENDPOINT`, derived from `OTEL_EXPORTER_OTLP_ENDPOINT` (default
`http://otel-collector:4318`; Boot samples 10% of requests). Internal only: default network, no
host ports, no domain.

| Env | Default | Effect |
| --- | --- | --- |
| `OTELCOL_EXPORT` | `debug` | Exporter overlay `deploy/otel/export-<name>.yaml`. `debug` logs a summary per batch and sends nothing off-host |
| `OTELCOL_OTLP_ENDPOINT` | — | Required when `OTELCOL_EXPORT=otlphttp`: backend base URL (`/v1/traces`, `/v1/metrics` are appended). Unset → the collector refuses to start |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://otel-collector:4318` | Where the api sends traces |

A backend that needs an auth header, or lives on `dokploy-network`, gets its own overlay (and the
network) in the PR that provisions it. Check it runs: `docker logs <otel-collector>` shows
`ResourceMetrics`/`ResourceSpans` summaries every batch under the debug overlay.

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

Migrations are applied at api start, inside the boot window — plain
`CREATE INDEX` statements (e.g. `V38__fk_covering_indexes.sql`) take `SHARE`
locks that block writes on the target tables while they build. On a large
table that stalls the api's own boot healthcheck window; schedule releases
carrying index migrations for a low-traffic window.

## Backups

No in-repo automation backs up the database — Dokploy-level/volume snapshots
of the `octo-supabase-db` service are the only recovery path today.
`deploy/backup.sh` is a manual `pg_dump` wrapper for ad-hoc archives:

```bash
PGPASSWORD=… POSTGRES_HOST=octo-supabase-db ./deploy/backup.sh
# writes backups/octo-<db>-<utc-timestamp>.dump.gz, prunes files older than KEEP_DAYS
```

Restore with `gunzip -c <archive> | psql -h <host> -U octo -d <db>`. Point
`POSTGRES_HOST` at a host that reaches Postgres — inside Dokploy, run it on
the host with `docker exec` against the supabase db container, or via the
bolt-bridge pattern above. A scheduled runner (cron/systemd on the VPS) is a
deliberate follow-up, not part of this compose.

## Rollback

The compose is stateless — redeploy the previous branch commit from Dokploy.
The api is the only service that mutates state, exclusively via Flyway, and
migrations are forward-only; roll code forward, do not revert applied
migrations.
