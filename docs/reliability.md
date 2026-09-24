# Reliability — SLOs, error budget, and production readiness

How Mesta-Asset measures whether it is working for its users, and what happens when it is not. Owner: Platform, with the api module's CODEOWNERS. Reviewed quarterly and after every SEV-1. Issue #73 started it.

Nothing here is measured yet: the api exposes no metrics a scraper can read (see the readiness review). The SLOs below are the targets the observability baseline is built to measure, set from the user journeys in [user-workflows.md](user-workflows.md), not from history.

## 1. Service level indicators and objectives

All windows are rolling 30 days. Measurements come from the api's own request metrics (Micrometer `http.server.requests`) and from the database, never from a client.

| Journey | SLI | SLO | Why this number |
| --- | --- | --- | --- |
| Any screen loads (WF-1, WF-2) | share of api requests answered with a non-5xx status, excluding `/actuator/**` | **99.9%** | 43 minutes of failure per month. A PM starts the day in the Control Panel; an hour-long outage is noticed the same hour |
| Drill-down feels immediate (WF-2) | share of read requests (`GET`) under 500 ms at the server | **99%** | Portfolio, fund, and deal pages are table reads; the browser adds its own latency on top |
| A recorded event is durable (WF-3, WF-4, workflow) | share of write requests (`POST`/`PUT`) to ledger, workflow, and audit endpoints that commit, excluding client-rejected 4xx | **99.95%** | A lost capital call or approval is a reconciliation break and an audit gap. Writes are rarer than reads and must almost never fail |
| Reports come out on time (WF-4) | share of report jobs (slice 7) finishing within 15 minutes of submission | **99%** | Quarter-end packs run in hours today; a 15-minute job budget keeps that promise |
| Data is fresh (WF-3) | share of scheduled ingestion runs completing within their schedule interval | **99%** | A stale source shows up as a recon discrepancy the next morning either way; the SLI makes it visible the same hour |
| The service is deployable | share of deploys where readiness turns healthy within `start_period` (90 s) and migrations apply without manual action | **100%** over the last 10 deploys | A failed migration on a Flyway-managed append-only schema is a stop-the-line event |

Excluded from the availability and latency SLIs: planned maintenance announced 24 hours ahead, and requests rejected at the auth boundary (401/403 are the client's problem by policy).

## 2. Error budget

`budget = 1 − SLO`. For availability at 99.9% that is 0.1% of requests, or about 43 minutes of total failure per 30 days.

| Budget consumed in the window | Policy |
| --- | --- |
| under 50% | normal delivery |
| 50–80% | no T2 deploys except fixes; one reliability item joins each slice PR |
| over 80% | release freeze except fixes; the owner is told in the #6 thread |
| exhausted | incident review; the next planned slice is replaced by reliability work |

Product and engineering agree this table before an outage, not during one.

## 3. Alerts

Multi-window burn-rate alerts on the availability and durability SLIs, once Prometheus scrapes the api:

| Window | Burn rate (99.9% SLO) | Severity | Runbook |
| --- | --- | --- | --- |
| 1 h | 14.4× | page | check readiness, then the database; roll back the last deploy if it started with it |
| 6 h | 6× | page | same |
| 3 d | 1× | ticket | reliability backlog item |

Non-SLO alerts that page: readiness failing for more than `start_period` after a deploy; Flyway reporting a failed migration; the audit chain failing verification (`verifyAuditChain` returns a break).

Every page links to a runbook. Alerts without an action are deleted.

## 4. Production readiness review

State of `main` on 2026-09-24. ✅ passes, ⚠️ acceptable for now, ❌ must fix before the first production deploy.

| Area | Check | State | Evidence and fix |
| --- | --- | --- | --- |
| Probes | liveness and readiness separated | ❌ → ✅ after the baseline PR | `management.endpoint.health.probes.enabled`, DB in the readiness group |
| Probes | health says what failed | ❌ → ✅ | `show-details: when_authorized`: probes see a status, operators see components |
| Metrics | a scraper can read request and JVM metrics | ❌ → ✅ | Prometheus registry on `/actuator/prometheus`, authenticated |
| Tracing | a request can be followed across logs and database rows | ❌ → ✅ | `CorrelationIdFilter`: one id per request in the MDC, the response, and every `correlation_id` column |
| Logs | machine-readable, no free-text personal data | ❌ → ✅ | ECS-format JSON on the console; the governance rule against logging tokens and personal data still applies to what code puts in a message |
| Config | every env var the compose file passes is read by something | ❌ | `OTEL_EXPORTER_OTLP_ENDPOINT` is passed and read by nothing. Wire an OTLP exporter when a collector exists, or drop the variable |
| Build | the image the compose file pulls is built from this repo | ❌ | there is no `Dockerfile`; AGENTS.md lists one. DevOps item |
| Runtime | JVM heap sized to the container | ⚠️ | 2 GB limit, default heap 25%. Set `-XX:MaxRAMPercentage=75.0` in the image or compose (infra review) |
| Runtime | compose healthcheck uses readiness | ⚠️ | it uses the aggregate; switch to `/actuator/health/readiness` after the baseline PR (infra review) |
| Dependencies | api waits for what it needs | ⚠️ | `depends_on` covers TypeDB only; PostgreSQL lives in another compose project, so readiness plus `start_period` is the real gate |
| Auth | fails closed without a JWKS URL | ✅ | `SecurityConfig` |
| Data | migrations run as a separate role; the runtime role cannot update or delete | ✅ | `DB_MIGRATION_*`, V3, `RuntimeRoleGrantsIT` |
| Data | backups and point-in-time recovery | ❌ | ADR-0002 assigns it to the operator; no runbook exists (system-design.md marks it ⬜) |
| Release | rollback path for every migration | ⚠️ | each PR states one; none has been rehearsed. AGENTS.md requires a tested rollback for T2 |
| Testing | integration tests run in CI | ❌ | CI is blocked by GitHub billing; the ITs run only on developer machines |

## 5. Reliability backlog, ranked by SLO impact

1. **Restore CI.** Nothing merges with a green check today. Blocks every other item's verification.
2. **Observability baseline** (this issue's PR 2): probes, Prometheus, correlation ids, structured logs. Without it none of the SLIs can be measured.
3. **Dockerfile and image build.** The compose file references an image nobody builds.
4. **Backup and restore runbook**, with one rehearsed restore. The ledger is append-only, so a lost database is unrecoverable by replay.
5. **Compose follow-ups:** healthcheck on readiness, `MaxRAMPercentage`, drop or wire the OTEL variable.
6. **Migration rollback rehearsal** for V5–V7 on staging, as AGENTS.md requires for T2.
7. **Timeouts on outbound calls.** `JdkHttpTransport` has a 30-second request timeout; the decision-model call sits on the ingestion path, so a slow vendor becomes a slow ingest. Add a circuit breaker once the call is on a user-facing path.
8. **Burn-rate alerts** in the collector once metrics flow.

## 6. What this document does not cover

On-call rotation, severity levels, and the postmortem process (incident management); the Supabase project's own reliability (operator, ADR-0002); load testing (performance).
