# Reliability — SLOs, error budget, and production readiness

How OCTO measures whether it is working for its users, and what happens when it is not. Owner: Platform, with the api module's CODEOWNERS. Reviewed quarterly and after every SEV-1. Issue #73 started it.

Nothing here is measured against a target yet: the OTEL collector scrapes the api's metrics and receives its traces (#306), but no backend stores them — the default exporter only logs (see the readiness review). The SLOs below are the targets the observability baseline is built to measure, set from the user journeys in [user-workflows.md](user-workflows.md), not from history.

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

Multi-window burn-rate alerts on the availability and durability SLIs, once the collector exports to a backend that can evaluate them:

| Window | Burn rate (99.9% SLO) | Severity | Runbook |
| --- | --- | --- | --- |
| 1 h | 14.4× | page | check readiness, then the database; roll back the last deploy if it started with it |
| 6 h | 6× | page | same |
| 3 d | 1× | ticket | reliability backlog item |

The availability rule pack and deterministic tests are in [`deploy/alerts/`](../deploy/alerts/README.md),
with paired short windows of 5m, 30m and 6h respectively. The pack requires a
Prometheus-compatible evaluator and verified deployment/metric labels; it is not
active under the collector's debug exporter. Durability rules remain pending
transaction-outcome instrumentation. The availability pack also does not yet
implement the planned-maintenance exclusion; notification silences do not remove
maintenance requests from the SLI. See its README for activation requirements
and the [availability runbook](runbooks/availability-burn.md) for operator actions.

Non-SLO alerts that page: readiness failing for more than `start_period` after a deploy; Flyway reporting a failed migration; the audit chain failing verification (`verifyAuditChain` returns a break).

Every page links to a runbook. Alerts without an action are deleted.

## 4. Production readiness review

State of `main` on 2026-09-27. ✅ passes, ⚠️ acceptable for now, ❌ must fix before the first production deploy.

| Area | Check | State | Evidence and fix |
| --- | --- | --- | --- |
| Probes | liveness and readiness separated | ❌ → ✅ after the baseline PR | `management.endpoint.health.probes.enabled`, DB in the readiness group |
| Probes | health says what failed | ❌ → ✅ | `show-details: when_authorized`: probes see a status, operators see components |
| Metrics | a scraper can read request and JVM metrics | ✅ | `/actuator/prometheus` needs a JWT on the public port; anonymous only on the internal, unrouted `OCTO_METRICS_PORT` (8081) connector (#338, `MetricsPortTest`) |
| Tracing | a request can be followed across logs and database rows | ❌ → ✅ | `CorrelationIdFilter`: one id per request in the MDC, the response, and every `correlation_id` column |
| Logs | machine-readable, no free-text personal data | ❌ → ✅ | ECS-format JSON on the console; the governance rule against logging tokens and personal data still applies to what code puts in a message |
| Config | every env var the compose file passes is read by something | ✅ | `OTEL_EXPORTER_OTLP_ENDPOINT` is back (#306): compose maps it onto `MANAGEMENT_OTLP_TRACING_ENDPOINT`, which Boot's OTLP span exporter reads |
| Telemetry | metrics and traces leave the process | ⚠️ | `otel-collector` (pinned core image) scrapes `api:8081/actuator/prometheus` and receives OTLP traces; exporter defaults to `debug` (logs only) until `OTELCOL_EXPORT=otlphttp` points at a backend |
| Build | the image the compose file pulls is built from this repo | ✅ | `Dockerfile` (#76): wrapper-built boot jar on a JRE, non-root |
| Runtime | JVM heap sized to the container | ✅ | `MaxRAMPercentage=75.0` and exit-on-OOM in the image (#76) |
| Runtime | compose healthcheck uses readiness | ✅ | `/actuator/health/readiness` (#76); `ReadinessIT` shows it drops when the database is lost |
| Dependencies | api waits for what it needs | ⚠️ | `depends_on` covers the graph store only; PostgreSQL lives in another compose project, so readiness plus `start_period` is the real gate |
| Auth | fails closed without a JWKS URL | ✅ | `SecurityConfig` |
| Data | migrations run as a separate role; the runtime role cannot update or delete | ✅ | `DB_MIGRATION_*`, V3, `RuntimeRoleGrantsIT` |
| Data | backups and point-in-time recovery | ⚠️ | ADR-0002 assigns it to the operator; [restore-runbook.md](restore-runbook.md) now covers the procedure — a rehearsed restore is still pending, and blocked: the deployed DB has `archive_mode = off`, so there is no WAL archive to rehearse PITR from (#301) |
| Release | rollback path for every migration | ⚠️ | each PR states one; none has been rehearsed. AGENTS.md requires a tested rollback for T2 |
| Testing | integration tests run in CI | ✅ | `ci` runs `./gradlew check` on every PR and `main` push, Testcontainers ITs included (only `DecisionModelEvalTest`, which needs a live model key, skips); `main` green since b38ab253 (#304) |

## 5. Reliability backlog, ranked by SLO impact

1. ~~Restore CI~~ — runs again on every PR and `main` push; the last red job (`docker-scan`) was fixed in #477/#480 (#304).
2. ~~Observability baseline~~ — probes, Prometheus, correlation ids, and structured logs all ship (§4).
3. ~~Dockerfile and image build~~ — done in #76.
4. ~~Backup and restore runbook~~ — `docs/restore-runbook.md`; the rehearsed restore half is still open.
5. ~~Compose follow-ups~~ — healthcheck on readiness and `MaxRAMPercentage` in #76; the OTEL variable returns with the collector (#306).
6. **Migration rollback policy for V5–V7** — decision recorded from the #302 dependency inventory: a pre-V5 state is not a recovery target (it loses every `workflow_task*`, the whole `audit_event` chain, and all approvals, while leaving dangling `task_id` references), and the V5–V7 files cannot be inverted because V28/V37/V43 have since moved and replaced their objects. So applied migrations are answered by a corrective forward migration; restore-from-backup is reserved for data loss. The rehearsal harness now guards the version window (`validate_rehearsal_window` in `deploy/drill/lib.sh`, with floor and top derived from `db/migrations/`), keeping V(n−1)→V(n) rehearsals executable for later migrations.
7. **Timeouts on outbound calls.** `JdkHttpTransport` has a 30-second request timeout. No running service calls the decision model yet: `DocumentClassifier` and `ClaimSupportAssessor` are not wired into any service, and only `DecisionModelEvalTest` constructs the client. The PR that wires that call into a running service adds the circuit breaker, so that a slow vendor fails fast instead of stalling the caller (#305).
8. **Burn-rate alerts.** ~~OTEL collector~~ ships (#306) and metrics flow into it; next is a backend behind `OTELCOL_EXPORT=otlphttp` that stores them and evaluates §3.

## 6. What this document does not cover

On-call rotation, severity levels, and the postmortem process (incident management); the Supabase project's own reliability (operator, ADR-0002); load testing (performance).
