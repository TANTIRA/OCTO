# Incident runbook — severity, declaration, and first response

What to do when OCTO is failing for its users. Owner: Platform, with the api module's CODEOWNERS. This fills the incident-management gap [reliability.md](reliability.md) §6 excludes: severity levels, who declares and who is told, the alerts that page, a playbook per failure class, log handling, and the record each incident leaves. The alerts come from reliability.md §3; this is what each of them links to.

**T2 by nature.** Anything writing financial data, touching auth, or running a migration is T2 ([AGENTS.md](../AGENTS.md) risk tiers). A fix goes through the normal review path — never edit a migration that has already run, and never revert one ([deploy/README.md](../deploy/README.md), "Rollback").

## 1. Severity

Declare from the user-visible effect, not the suspected cause; the reviewer adjusts it afterwards.

| Severity | Definition (SLO anchor) | Examples | Ack |
| --- | --- | --- | --- |
| **SEV-1** | A journey is down, or a committed write or its audit trail is not durable: availability burning ≥ 14.4×/1 h or ≥ 6×/6 h (reliability.md §3), or a confirmed loss of a committed ledger/workflow/audit write (99.95% SLO) | api not answering; any lost capital call, approval, or audit row; `verifyAuditChain` returns a break; a failed Flyway migration; readiness unhealthy past `start_period` (90 s) after a deploy — the deployability SLI is 100% over the last 10 deploys | ≤ 15 min |
| **SEV-2** | One journey degraded while the platform still serves: reads and ledger writes work, one workflow family or one SLI band fails | every agent workflow answering 503 while the ledger is fine; report jobs missing the 15-minute budget (99% SLO); scheduled ingestion late (99% SLO); `GET` latency over the 500 ms target (99% SLO) without errors | ≤ 1 h |
| **SEV-3** | Single item, no budget burn worth paging | one stuck task, one tenant edge case, one vendor run missed, a web surface degraded while the api is healthy; the 3 d/1× ticket row of reliability.md §3 | ≤ 1 business day |

The clock starts at the first observation, not the root cause. A 1 h window at 14.4× spends 2% of the 30-day budget (43 min, reliability.md §2) and would exhaust it in about 50 h; 6× over 6 h spends 5% — that is why SEV-1 acknowledges inside 15 minutes.

**The severity scale and the acknowledgement targets are proposed here, not inherited.** reliability.md defines no severity table and names no response times, and there is no on-call rotation (§8). They are derived from reliability.md's own SLO and error-budget arithmetic, and they need the Platform owner's agreement before they are treated as policy — the same way reliability.md §2's budget table is stated as agreed before an outage rather than during one.

SEV-1 immediately, whatever the arithmetic, are the governance classes in [data-security-governance.md](data-security-governance.md) ("Incident and breach response"): suspected credential exposure, unauthorized data access, material data corruption, cross-entity leakage, malicious AI tool use, loss of auditability. Planned maintenance announced 24 h ahead is excluded from the availability and latency SLIs (reliability.md §1) — but only if the notice went out first.

## 2. Who declares, who is told, what is expected

- **Declares:** anyone who can see the symptom — there is no rotation to wait for, and a wrong declaration is corrected in review, not punished. Until alerting is operational (§8) every declaration is manual.
- **SEV-1 tells** the api module's CODEOWNERS (`@TANTIRA/octo`) and the affected item's owner in [backlog-tracker.md](backlog-tracker.md), in the #6 thread — the surface reliability.md §2 already uses to tell an owner, and the roadmap governance thread (`WHITEPAPER.md` §13).
- **Governance classes** (§1) tell Security and Privacy/Legal at once; notification decisions and timelines are theirs, not engineering's.
- **Handover:** an incident longer than one working session gets a named owner per session, with open actions in the thread. A restore that will overshoot the 4 h RTO is a decision on the record, not a silent overrun ([restore-runbook.md](restore-runbook.md) §1).
- **SEV-2/3** tell the affected item's owner and open a backlog entry linking its source.

## 3. What pages

From reliability.md §3–§4. Every page links to a runbook; these are those runbooks.

| Signal | Alert | Source | Severity | Playbook |
| --- | --- | --- | --- | --- |
| Availability burn 14.4×/1 h | `OctoAvailabilityBurnFast` | api request metrics (Micrometer `http.server.requests`) | SEV-1 | §4 → §5.1, and the burn-rate runbook |
| Availability burn 6×/6 h | `OctoAvailabilityBurnSlow` | same | SEV-1 | §4 → §5.1, and the burn-rate runbook |
| Availability burn 1×/3 d | `OctoAvailabilityBurnTicket` | same | SEV-3 | reliability.md §5 backlog |
| Readiness failing beyond `start_period` (90 s) after a deploy | not yet an alert rule | `/actuator/health/readiness`, compose healthcheck | SEV-1 | §5.1 |
| Flyway reporting a failed migration | not yet an alert rule | api boot log, `octo.flyway_schema_history` | SEV-1 | §5.2 |
| `verifyAuditChain` returns a break | not yet an alert rule | chain verification (§5.4) | SEV-1 | §5.4 |

The three burn-rate rules and a runbook for them land with the alerting slice (#303); until it
merges, the rows above are what *will* page and every declaration is manual (§8). Rules live in
`deploy/alerts/` and are unit-tested there — read the merged rule, not this table, as the
authority on names and thresholds.

## 4. First 15 minutes

```bash
curl -sf https://api-octo.mesta.click/actuator/health             # liveness
curl -sf https://api-octo.mesta.click/actuator/health/readiness    # readinessState + db
curl -s -o /dev/null -w '%{http_code}\n' https://octo.mesta.click/app
```

- Both health paths are public on `api-octo.mesta.click`; anonymous callers get the status only, and a bearer token shows which component failed (`show-details: when_authorized`). Readiness includes `db`, so a lost database takes the instance out of rotation without killing it (`ReadinessIT`).
- **A 404 is not always "down".** Dokploy drops *unhealthy* containers from the router, so the domain 404s while the process still answers (deploy/README, "Gotchas"). Check the container, not only the domain.
- Containers and logs on the VPS: `VPS_getProjectContainersV1` and `VPS_getProjectLogsV1` (Hostinger VM `1943271`, project `octo-app-kbf88q`) are the fast path when Dokploy's own log procedures do not answer. From the host shell, in the cloned repo: `docker compose -f deploy/dokploy.compose.yml ps`, then `logs --tail=200 api`.
- Metrics: the api opens an anonymous `/actuator/prometheus` on `OCTO_METRICS_PORT` (8081) — never published, never routed, only stack networks reach it. From the host: `docker exec <api-container> curl -fsS localhost:8081/actuator/prometheus | head`, then read `http_server_requests_seconds`.
- Flyway history (a stale-looking migration is usually a boot that never reached it):
  ```sql
  select installed_rank, version, description, success, installed_by, installed_on
  from octo.flyway_schema_history order by installed_rank desc limit 5;
  select count(*) from octo.flyway_schema_history where not success;
  ```
  `installed_by` should be `octo_migrate`.
- Follow one request: `CorrelationIdFilter` puts `correlation_id` in the MDC, the response, and every `correlation_id` column — grep the logs for the failing request's id instead of guessing.
- **Capture before you recreate.** Container stdout is bounded (`json-file`, `max-size: 10m`, `max-file: 3`) and there is no log backend yet (§8), so `docker logs` on the failing container and the collector's batch summaries are the only copies.

## 5. Playbooks

**5.1 Bad deploy** — readiness never healthy past 90 s, 5xx jump, restart loop, or a domain that 404s while the container answers.
1. Env change or key rotation? `docker compose up -d` only recreates services whose rendered config changed, so a Supabase-side key/env change leaves the api with a stale JWKS cache — restart the app project (`VPS_restartProjectV1`). This is the documented fix, not a workaround.
2. Redeploy vs deploy: `compose.redeploy` reuses the built image; a compose or env change needs a full `compose.deploy` (rebuild + recreate). Env writes go through `compose.update` with the complete env blob — round-trip via `compose.one` and diff before writing.
3. Rollback: the stack is stateless — redeploy the previous branch commit from Dokploy. Image tags carry the commit sha (`API_IMAGE_TAG`/`AGENTS_IMAGE_TAG`/`WEB_IMAGE_TAG`, e.g. `main-cf5c269`), so the running image names its own build.
4. Stalled inside the boot window? Plain `CREATE INDEX` migrations (e.g. V38) take `SHARE` locks that block writes while they build, which can outlast the healthcheck window on a large table. Fix the schedule, not `start_period`.
5. Never revert an applied migration to speed the rollback — see 5.2.

**5.2 Failed Flyway migration** — the api never becomes ready, the boot log names the migration, `octo.flyway_schema_history` has a `success = false` row or a missing version. Flyway validates history on boot and fails loudly.
1. **Migrations are forward-only by decision** (deploy/README "Rollback"; `WHITEPAPER.md` §11): write a new migration that repairs or redoes the work. Never revert, edit, or delete an applied one.
2. Rehearse the repair migration on staging first: migration rollback rehearsal is an open T2 requirement (reliability.md §5 item 6, backlog-tracker.md item 4).
3. If the history is inconsistent or the schema is damaged, the recovery is a restore, not a hand-edit — [restore-runbook.md](restore-runbook.md) §4 (point-in-time) and §5 (surgical) own that procedure; this runbook does not duplicate it.
4. A history row written by anything other than `octo_migrate` is an integrity problem — the runtime role cannot update or delete (V3, `RuntimeRoleGrantsIT`). Escalate SEV-1 and preserve the database before further writes.

**5.3 Database loss or corruption** — readiness unhealthy with `db` among the failed components, connection errors, writes failing while the process lives.
1. Go to [restore-runbook.md](restore-runbook.md): RPO ≤ 15 min, RTO ≤ 4 h, quarterly rehearsed (§1). Do not improvise a restore from here.
2. Backups are operator infrastructure (ADR-0002). The repo has no backup automation: Dokploy-level/volume snapshots of `octo-supabase-db` are the only recovery path today, with `deploy/backup.sh` a manual `pg_dump` wrapper for ad-hoc archives (deploy/README, "Backups"), and restore-runbook §3's base backup + WAL archive is the layer the procedure assumes.
3. Keep the loss window start, the chosen restore target time, and the verification evidence (restore-runbook §7) for the incident record (§7).

**5.4 Audit chain break** — `verifyAuditChain` returns a `ChainBreak(seq, reason)`: "a row is missing or out of order", "prev_hash does not match the previous row's hash", or "hash does not match the row's contents" (`AuditChain.kt`). `seq` is the first failing link.
1. **SEV-1, always:** the tamper-evident log cannot be trusted — escalate as a loss of auditability (data-security-governance.md).
2. Nothing runs the check on a schedule (§8) and no HTTP endpoint exposes the chain. It is a privileged read of `octo.audit_event` plus `verifyAuditChain` over rows in `seq` order; the api's runtime role cannot even read the table (V6 grants `INSERT` only, `AuditLogIT` asserts `INSUFFICIENT_PRIVILEGE`).
3. Preserve before touching: snapshot the database, record the `seq` and reason, treat rows from `seq` forward as suspect. The `audit_event_append_only` and `audit_event_no_truncate` triggers refuse UPDATE, DELETE, and TRUNCATE.
4. State the ceiling in the record: V6 documents that a superuser can rewrite the chain consistently, or cut off its newest rows, and verification still returns clean; anchoring the head hash outside the database is the follow-up.

**5.5 Auth / JWKS failure** — every bearer token 401s at once, or JWKS fetches fail in the log.
1. `curl -s https://supa-octo.mesta.click/auth/v1/.well-known/jwks.json`. `{"keys":[]}` means GoTrue has no asymmetric key material and the api's JWKS decoder rejects every token. ES256 needs `JWT_KEYS`/`JWT_JWKS`; the Dokploy helpers cannot make EC keys, so they come from `utils/add-new-auth-keys.sh` run from supabase/supabase with the deployment's `JWT_SECRET`.
2. `AUTH_JWKS_URL` must be the **internal** Kong path (`http://octo-supabase-tonh7d-kong-1:8000/auth/v1/.well-known/jwks.json`), not the public URL: the api container cannot hairpin back out through Traefik, so a public JWKS URL fails and every token 401s.
3. After rotating signing material, restart the app project (`VPS_restartProjectV1`) — env changes do not recreate containers, and the api holds a stale JWKS cache.
4. **Do not blank `AUTH_ISSUER`/`AUTH_AUDIENCE` to stop the 401s:** with them blank, issuer/audience validation is skipped and any JWKS-signed token — including the publishable anon key — authenticates (backlog-tracker.md item 21). Fail closed.
5. **The SLO will not page for this.** 401/403 at the auth boundary are excluded from the availability SLI (reliability.md §1), so the JWKS check above is the detection.
6. Verify recovery with a real sign-in and `/api/v1/me/access` (the `platformAdmin` flag), using `octo-ops-smoke@test.invalid` before asking a person to retry.

**5.6 Supabase Auth or Storage unavailable** — Auth is GoTrue behind `supa-octo.mesta.click` (Kong :8000), its own Dokploy compose project. Existing sessions keep working while the api's cached key set validates them; new sign-ins stop. Storage is narrower than it sounds: the deployed api reads no `SUPABASE_*` variables at all (#340), so document upload/download degrades while the ledger, reconciliation, workflow, and report endpoints keep serving — do not restart the api for a Storage outage, there is nothing in it to fix. Check the Supabase project's containers and the proxy route; this is operator infrastructure (ADR-0002) with its own backups and restore path (restore-runbook §2). Record it as SEV-1 for sign-in with the dependency named, not as an api incident.

**5.7 Redis unavailable** — the api keeps enforcing with in-process counters and **never fails open**, logging one warning per 30 s of outage (deploy/README "Rate limiting"). Quotas become per-instance instead of shared: with more than one api replica a tenant gets the quota per replica, and `ClientIpRateLimitFilter`'s 30 failed authentications/min per IP (10/min on anonymous `POST /api/v1/contact`) is likewise per-instance. Nothing durable lives in Redis — no volume, current-minute counters only — so this is not data loss, and its healthcheck is `redis-cli ping`. SEV-3 normally; if the per-IP limit is what is holding back a credential-stuffing run, escalate as a security incident (data-security-governance.md, "repeated denials").

**5.8 Agent sidecar outage** — agent endpoints answer 503, workflow runs fail. The api maps an unreachable sidecar to 503 and a non-2xx sidecar answer to 502 (503 if the sidecar said 503); `OCTO_AGENTS_BASE_URL` unset means no sidecar is deployed, and the bean still exists but fails on use with 503 rather than looking like a client error (`AgentsConfiguration`); the deployed default is `http://agents:8080`. Blast radius is the agent workflows only — the ledger, reconciliation, tasks, reports, and auth do not go through the sidecar, so this is SEV-2. A slow sidecar holds the calling request up to 120 s (10 s connect) in `JdkAgentsClient`, so expect thread pile-up before errors. The sidecar's own unconfigured state (missing OpenRouter key or shared token) returns 500 on every endpoint including flag-off ones (backlog-tracker.md item 26) — a configuration problem, not proof of an outage. `agents` waits on the api's readiness healthcheck (`depends_on: service_healthy`), so when both look down, check the api first.

## 6. Logs: retention, access, scrubbing

Logs are centralized, access-restricted, time-synchronized, retention-controlled, and scrubbed of tokens and sensitive payloads (ADR-0002, "Monitoring"). Application logs must not contain credentials, tokens, full sensitive documents, or unnecessary personal and financial data (data-security-governance.md, "Logging, audit, and monitoring").

- **Format:** ECS-format JSON, one object per line on the console, with `correlation_id` from the MDC (`application.yml`, `CorrelationIdFilter`).
- **Retention:** container stdout is bounded by the compose logging options — `json-file`, `max-size: 10m`, `max-file: 3` on api, agents, web, redis, and otel-collector. No log backend exists yet, so nothing outlives those files; capture what the record needs before recreating a container, which discards them. Audit rows are not logs: they live in `octo.audit_event`, covered by database backup and retention, not by the log pipeline.
- **Scrubbing:** retention is never a reason to log payloads. `audit_event.details` holds references only — ids, hashes, versions — enforced as a JSON object at the database level (V6). Use the `correlation_id` to reach the row instead of widening a log line.
- **Access:** never paste raw log lines, tokens, headers, or payloads into the incident thread. Quote the `correlation_id`, the action, the status code, and the component; redact the rest. Secrets never go into tickets or documentation.
- **Time:** audit `recorded_at` comes from the database clock, log timestamps from the container clock. Take measurement timestamps from one clock and keep host and container clocks synchronized — a drifted VPS clock makes a correlation timeline lie.

## 7. Evidence and records

Each incident records, in its thread: id and severity; declared / mitigated / resolved times in UTC; the commit sha and image tags that were running; the journey affected, the window, and whether the availability or durability SLI moved and by how much of the 43-minute monthly budget; the signal that caught it and the signal that should have; a timeline with the `correlation_id`s and query output behind it; the root cause and the fixing PR; whether `verifyAuditChain` passed before and after; and the follow-ups.

- Follow-ups become [backlog-tracker.md](backlog-tracker.md) entries, each linking its source — that file is the source of truth for "known but not done".
- Review is the Platform owner with the api module's CODEOWNERS; reliability.md is reviewed after every SEV-1 and quarterly, and its §5 backlog is re-ranked by SLO impact.
- A restore or rehearsal is recorded on [drill-evidence-template.md](drill-evidence-template.md) and its result lands on reliability.md §4; ADR-0002's acceptance boxes are ticked only from a filled record on staging, never from documentation.
- Governance classes additionally follow [data-security-governance.md](data-security-governance.md), where Security and Privacy/Legal own notification.

## 8. What this does not cover

- **Alerting is not operational.** The collector scrapes `api:8081/actuator/prometheus` and receives OTLP traces, but its default exporter is `debug`: it logs a batch summary and sends nothing off-host (deploy/README "OTEL collector", `deploy/otel/export-debug.yaml`; reliability.md §4, §5 item 8). No burn-rate alert can fire until a backend sits behind `OTELCOL_EXPORT=otlphttp` (backlog-tracker.md item 5) — **this runbook is currently invoked manually**, by a person who noticed.
- **No on-call rotation and no paging provider.** reliability.md §6 excludes incident management and this document does not invent a rotation; the acknowledgement targets in §1 are expectations on the people already working, not a contract with a pager.
- **The Supabase project's own reliability** — Auth, Storage, Kong, the database host, backups — is the operator's (ADR-0002). This runbook names the dependency and stops.
- **Post-incident depth:** §7 defines the record, not a facilitated blameless-postmortem process; reliability.md is the document that must change as a result of an incident.
- **Capacity and load:** a capacity incident has no playbook here — ADR-0002 ("Availability and scaling") still requires the user, throughput, pool, and peak numbers that would size one. Capacity and dependency-failure rehearsals are recorded on [drill-evidence-template.md](drill-evidence-template.md).
