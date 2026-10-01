# Backlog tracker

One list for open engineering and ops items that outlived the PR or doc that
surfaced them. Source of truth for "known but not done": new entries carry a
link back to their source; completed entries are removed, not annotated.

Last triage: 2026-09-29 — full real-code gap audit folded in (items 11+).

## Contributors

| Handle | Area |
| --- | --- |
| [@Venkat5599](https://github.com/Venkat5599) | Finances Engineering |
| [@fikriaf](https://github.com/fikriaf) | AI/ML ops |
| [@mzf11125](https://github.com/mzf11125) | Blockchain Protocol |
| [@Fatihmaull](https://github.com/Fatihmaull) | API Engineering |
| [@daemon-blockint-tech](https://github.com/daemon-blockint-tech) | Fullstack |
| [@Demcruise](https://github.com/Demcruise) | UI/UX |
| [@Aldroun](https://github.com/Aldroun) | Site Reliability Engineering |
| [@EliteSlacker](https://github.com/EliteSlacker) | Cyberdefense |
| [@kzahiras21](https://github.com/kzahiras21) | AI/ML ops |
| [@mapleury](https://github.com/mapleury) | Frontend UI |

## Ops — environment work with no repo diff

| # | Item | Source | Owner | Status |
| --- | --- | --- | --- | --- |
| 1 | Set `GOTRUE_EXTERNAL_WEB3_SOLANA_ENABLED=true` on the Dokploy Supabase `auth` service and redeploy — the SIWS button ships in web (PR #237) but self-hosted GoTrue ignores the legacy `SOLANA_ENABLED` name | `infra/supabase/vendor/CONFIG.md`, `vendor/docker-compose.yml` | @mzf11125 (feature) / @Aldroun (deploy) | open |
| 2 | Neo4j Browser remote access — keep tunnel-only path or expose bolt through a TLS-terminating Traefik TCP router (public bolt behind neo4j auth; contra the "bolt stays private" posture) | `deploy/README.md` ("Neo4j Browser access"), ADR-level | @EliteSlacker (decision) / @Aldroun (exec) | decided — Option A (tunnel-only), [ADR-0006](adr/0006-neo4j-bolt-exposure.md) |
| 3 | Rehearsed restore — procedure exists, drill pending | `docs/restore-runbook.md`, reliability §4 | @Aldroun | open |
| 4 | Migration rollback rehearsal for V5–V7 on staging (T2 requirement) | `docs/reliability.md` §5, `AGENTS.md` | @Aldroun | open |
| 5 | Burn-rate alerts — metrics now reach the collector (#306); needs an alerting backend behind `OTELCOL_EXPORT=otlphttp` | `docs/reliability.md` §3–5 | @Aldroun | open |

## Reliability backlog (docs/reliability.md §5)

| # | Item | Owner | Status |
| --- | --- | --- | --- |
| 6 | Restore CI — GitHub billing blocker; nothing merges green today. Blocks verification of everything below | @daemon-blockint-tech (billing/admin) | blocked |
| 7 | Circuit breaker on the decision-model call (`JdkHttpTransport` 30 s) once it sits on a user-facing path | @Fatihmaull | open |
| 8 | OTEL collector — prerequisite for item 5 | @Aldroun | done — `otel-collector` in `deploy/dokploy.compose.yml` (#306) |

## ADR acceptance criteria still open

| # | Item | Source | Owner | Status |
| --- | --- | --- | --- | --- |
| 9 | Self-hosted Supabase acceptance list: restore drill meeting RPO/RTO, upgrade/rollback rehearsal on staging, monitoring + incident runbooks, capacity/dependency-failure tests | `docs/adr/0002-self-hosted-supabase.md` | @Aldroun | open |
| 10 | Neo4j: dual-write ingestion path with atomic failure semantics; graph-ledger reconciliation on seeded data; ~~backup/restore + upgrade runbooks~~ (done — `docs/restore-runbook.md` §6, `docs/runbooks/neo4j-upgrade.md`, #308); look-through perf test over 5-level hierarchy | `docs/adr/0004-neo4j-graph-store.md` | @Fatihmaull | open |

## Gap audit — high (real code, verified 2026-09-29)

| # | Finding | Where | Owner |
| --- | --- | --- | --- |
| 11 | **Onchain ingestion broken under the runtime role** — `JdbcOnchainStagingStore` uses raw connections everywhere except `activeWatchedAddresses`; V30 RLS `with check` resolves NULL → inserts throw 42501, selects return empty. Every Helius delivery 500s; the EVM cursor never advances; snapshots fail. ITs can't see it — Testcontainers runs as superuser. | `modules/ingestion/.../JdbcOnchainStagingStore.kt:60-314`, `db/migrations/V30` | @mzf11125 |
| 12 | **Onchain→IBOR promotion never wired** — `JdbcInstrumentFlowStore`/`InstrumentFlowPromoter` only exist in `OnchainPromotionIT`; `instrument_flow` is never written in prod and its store is unscoped like #11. | `modules/ibor-core/.../JdbcInstrumentFlowStore.kt`, `modules/ingestion/.../OnchainPromotion.kt` | @mzf11125 + @Fatihmaull |
| 13 | **Report queue submits jobs that can never succeed** — posts `positionSourceType:"fund"` (accepted: only `inline-series`/`inline-events`), `netIrr` (produced: `irr`), `positionSourceId=tenantId`; and `load()` never calls `GET /reports/{id}`, so the 5s poll is a no-op and "Request release" is unreachable. | `web/components/report-queue.tsx:47-80`, `modules/api/.../ReportRunner.kt:82-140` | @mapleury + @Venkat5599 (contract) |
| 14 | **`/admin` ops page is public** — unauthenticated, prints API base + live health; routed publicly (`admin-octo.mesta.click`) and linked from the in-app command menu. | `web/app/admin/page.tsx`, `web/middleware.ts:6-13` | @EliteSlacker + @mapleury |
| 15 | **`finish_run` inside `except` can throw → audit loss** — bookkeeping failure masks the real error and the run row stays `running`; happy-path `finish_run` inside `try` can also trigger a second `finish_run` (completed→failed) or throw twice. All 7 workflows. | `agents/octo_agents/workflows/*.py` except handlers | @fikriaf |
| 16 | **Fake-as-live surfaces** — `dashboard-4` (hardcoded stats/alerts + fake refresh→"Updated just now"), `data-table-3` ("Asset register" edits "saved" via setTimeout, budgeting-template copy), positions rows hardcoded in `app-shell-2`. The default view of the gated app is fabricated. | `web/components/blocks/dashboard-4.tsx`, `data-table-3.tsx`, `web/components/blocks/app-shell-2.tsx:207-264` | @Demcruise |
| 17 | **Landing CTA form submits to nothing** — `onSubmit=preventDefault` only; every "Request access" on the page funnels to it. Terms/Privacy are `href="#"`. | `web/components/blocks/contact-10.tsx:103,231-237` | @mapleury |
| 18 | **Helius webhook verifies a signature exists, not the payload** — `ingest` checks `transaction.signatures[0]` is a finalized sig on-chain, then trusts submitted `accountKeys`/transfer legs. Secret-holder can fabricate transfers on watched wallets that promote to `instrument_flow`. | `modules/ingestion/.../OnchainWebhookService.kt:49-63`, `HeliusFinalityProbe.kt` | @mzf11125 + @EliteSlacker |
| 20 | **Sidecar tenant scoping caller-asserted** — every endpoint takes `tenant_id` from the body but reads/writes carry a bare `prospect_id`; a bad pair books tenant A's ledger on tenant B's prospect. `run_key` replay doesn't verify the stored row's subject/input — a key collision returns the wrong subject's memo. | `agents/octo_agents/server.py`, `workflows/screening_dd.py:_record_run` | @fikriaf + @Fatihmaull |

## Gap audit — medium

| # | Finding | Where | Owner |
| --- | --- | --- | --- |
| 21 | JWT issuer/audience validation skipped when `AUTH_ISSUER`/`AUTH_AUDIENCE` blank — any JWKS-signed token (incl. publishable anon key) authenticates; fail-open on misconfig. | `modules/api/.../SecurityConfig.kt:177-194` | @EliteSlacker |
| 22 | Bearer tokens, JWKS root of trust, shared secrets traverse `dokploy-network` plaintext HTTP (`OCTO_AGENTS_BASE_URL`, `OCTO_API_BASE_URL`, `API_INTERNAL_URL`, `NEO4J_URI`, JWKS URL). | `deploy/dokploy.compose.yml`, `deploy/README.md:38` | @EliteSlacker + @Aldroun |
| 24 | Floating tags/bases contradict the pinning guardrail: `*:_IMAGE_TAG:-latest`, `redis:7-alpine`, `eclipse-temurin:21-jdk/jre`, `node:22-alpine`×3, `python:3.12-slim`; image ignores `agents/uv.lock` (`pip install .` on `>=` ranges). | `deploy/dokploy.compose.yml`, `Dockerfile`, `web/Dockerfile`, `agents/Dockerfile` | @Aldroun |
| 25 | Agent sidecar runs as root — no `USER` in `agents/Dockerfile`. | `agents/Dockerfile:1-16` | @Aldroun |
| 31 | Calibration counts any unrecognized decision string as disagreement → inflated queue + inverted eval cases; only `DISAGREED` members should invert. | `agents/octo_agents/workflows/calibration.py:128-134` | @kzahiras21 |
| 33 | Sidebar subnav all dead (`href="#{area.id}"`); header action button ("New report"/"Run reconciliation") only opens ⌘K; shortcut hints decorative (only ⌘K bound); "Recent" entries fabricated. | `app-shell-2.tsx:822-842,1028-1047`, `command-menu-1.tsx:70-191,309-319` | @Demcruise |
| 34 | Command menu mouse-click executes the highlighted row, not the clicked one (`run(command)` then `run()`). | `command-menu-1.tsx:506-511` | @mapleury |
| 35 | Fabricated inputs sent to live endpoints — `compliance-panel` posts `subject:"portfolio"`, `currencyExposure:{USD:1}`; report queue sends tenant id as `positionSourceId`. Live evaluations/audit get fake data. | `web/components/compliance-panel.tsx:81-105`, `report-queue.tsx:74-80` | @mapleury + @Venkat5599 |
| 36 | Pipeline dead end: `NEXT` map stops at `ic-review`; `passed`/`invested` transitions (rationale+taskId) unreachable — IC-stage prospects go nowhere. | `pipeline-board.tsx:37-41`, `ProspectController.kt:310-323` | @Venkat5599 + @mapleury |
| 37 | Workspace switcher dead end ("Add vehicle" only closes); fake `WORKSPACES` remain as the real list when `/me/access` fails (`.catch(() => undefined)`). | `app-shell-2.tsx:108-112,638-649,912-929` | @mapleury |
| 38 | False empty states on cold load — `useTenants().loading` never consumed; panels render "Nothing here yet" before tenants resolve. | `lib/use-tenants.tsx:21`, `pipeline-board.tsx:60-63`, `agent-runs-panel.tsx:42-44` | @Demcruise |
| 39 | CSP `img-src` blocks `cta-2` Unsplash trail images in prod; `connect-src` would also block a real `NEXT_PUBLIC_SOLANA_RPC_URL`. | `web/next.config.ts:39-41`, `cta-2.tsx:7-14` | @mapleury |
| 40 | Actuator metrics/prometheus unreachable — JWT required, no scraper/service account/deployed Prometheus; deploy README's tuning promise never wired. | `modules/api/.../application.yml:34`, `deploy/README.md:113` | @Aldroun |
| 41 | `infra/docker-compose.yml` degraded variant: no redis/agents services, never forwards `HELIUS_*`/`ARBITRUM_*`/`ALPHA_VANTAGE_*`/`OCTO_AGENTS_*`/`OCTO_PLATFORM_ADMINS` it documents — those features silently can't run. | `infra/docker-compose.yml:19-88` vs `infra/.env.example:60-79` | @Aldroun |
| 43 | Non-atomic task-open + record: `ReconciliationRunner`/`ComplianceRunner` commit task then record in a second txn — failure between them leaves an orphan evidence-request/review task. | `ReconciliationRunner.kt:65-80`, `ComplianceRunner.kt:58-68` | @Fatihmaull |
| 44 | Tenant admins can raise their own rate limit (`PUT /admin/tenants/.../settings` writes any key incl. `rate_limit_per_minute`); any member can forge arbitrary `agent_run` audit rows (`subjectId` uncapped). | `AdminTenantsController.kt:130-153`, `AgentRunsController.kt:49-86` | @EliteSlacker |

## Gap audit — low (verified, summarized)

46. UI/UX: orphaned `kanban-1` (imported, never rendered) + `empty-state-1` + `/brain` route unlinked; hero search dead; faq/how-it-works/footer dead controls; AuthGate can hang forever (no `.catch`/retry on `getSession`); a11y gaps (unlabeled inputs, menu keyboard nav, mega-menu mouse-only, tab strip no roles); `globals.css` invalid `var(----rb-accent)`; `useScrollFade` ×4 dup; `brain-panel` reimplements `useTenants`; recon rows can't be deleted. → @Demcruise / @mapleury.

47. Agents: token compare not constant-time (`server.py:42`); `/docs`+`openapi.json` open; default `http://api:8080` plaintext; unencoded path/query interpolation; `admitted,_` discards RetrievalVerdict (ic_memo, dd); `NoulCriteria` dead contract; `ic_review_requested` false-negative on missing taskId; `list_pipeline` truncates silently at 50; raw `answers[...]` KeyError brittleness; replayed `model_validate` outside `try`; eval THRESHOLD conflates two knobs; `as_of` unvalidated; registry re-reads `models.yaml` per request; calibration `limit` unbounded/no pagination; no `test_server`/`test_api_client`/except-path tests; evals only for 2 of 7 workflows; `__getattr__` fakes can mask signature drift. → @fikriaf / @kzahiras21.

48. Infra: no scheduled backup runner — `deploy/backup.sh` is a manual `pg_dump` wrapper (deploy/README.md §Backups); a cron/systemd runner is a deliberate follow-up. Plaintext service-to-service traffic is tracked in item 22 / #320. → @Aldroun

    Note: `EvmEvidenceAdapter.kt` and `OnchainEvidenceAdapter` are planned ARB-8 work, not dead code — do not delete either.

## Verified clean this audit (do not re-flag)

AuthN/RLS: fail-closed throughout — all 44 controller mappings tenant-check; RLS functions `security definer` + pinned `search_path`; webhook secret constant-time SHA-256 compare, fail-closed; `AUTH_DEV_BYPASS` boot-refuses prod/JWKS; no secrets committed; Boot error defaults leak nothing; `package-lock` + `npm ci`, `uv sync --frozen`, SHA-pinned Actions, BOM-pinned Gradle deps; prepared statements only (no SQLi); no CORS surface (same-origin `/api` rewrite); Flyway V1–V38 contiguous; tasks/memberships/prospects serialize on advisory locks; report claims use `SKIP LOCKED` leases.

## Done / verified this cycle

- Web3 Solana sign-in (SIWS) — code and local GoTrue config verified; only the deployed-env flag remains (item 1).
- `ty` clean across `agents/` — 47 diagnostics resolved (question `Mapping`, `_record_run` run_id, `SubAgent` specs, `OctoApiClient` test fakes).
- V37 `search_path` pinning merged (#295); V38 FK covering indexes staged.
