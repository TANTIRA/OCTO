# Capacity and dependency-failure test plan

What must be measured before production launch, and what must be proven to degrade safely. Owner: Platform / DevOps. **T2.** This is the "capacity and dependency-failure tests pass" item of ADR-0002's production acceptance list; the plan is here, the passing results are not.

`docs/reliability.md` §1 sets the SLOs but records that **nothing is measured against them yet**. The thresholds below are therefore derived from those SLOs, not from history — they are the numbers a test is graded against, and they are open to revision once real measurements exist. ADR-0002 §Availability and scaling lists exactly what the deployment owner must document and test; this plan turns that list into runnable cases.

## 1. Load profile to establish first

ADR-0002 requires the expected figures be written down before thresholds mean anything. Stating "it handled the load" without these is not a result:

| Dimension | Value | Source |
| --- | --- | --- |
| Expected users, and concurrent users at peak | _to be established_ | product input |
| Ingestion throughput (events/minute per source) | _to be established_ | vendor adapters, `modules/ingestion` |
| Storage growth (GB/month: database, WAL, storage objects) | _to be established_ | measured on staging |
| Query concurrency and reporting peaks | _to be established_ | quarter-end report runs |

Until these exist, every threshold below is a ceiling to test against, not a capacity statement.

## 2. Capacity cases

| # | Case | Threshold (from the SLO) | How to drive it |
| --- | --- | --- | --- |
| C1 | Sustained reads — portfolio, fund, deal pages | 99% of `GET` under 500 ms at the server (reliability.md §1) | Concurrent read loop against the read endpoints; measure `http_server_requests_seconds` on the 8081 scrape port |
| C2 | Sustained writes — ledger, workflow, audit | 99.95% of writes commit (reliability.md §1) | Continuous append-only writes; confirm zero failed commits and a rising `seq` with no gaps |
| C3 | Ingestion peak and backlog drain | 99% of scheduled runs complete inside their interval (reliability.md §1) | Replay a batch at peak rate; measure drain time and whether the cursor keeps advancing |
| C4 | Reporting peak | 99% of report jobs finish within 15 min (reliability.md §1) | Submit the quarter-end shape concurrently; watch the claim/lease path and report backlog |
| C5 | Connection-pool saturation and backpressure | Pool never exhausts; requests queue rather than fail | Drive concurrency past `pool_size`; watch pool wait time and whether errors appear |
| C6 | WAL, checkpoint, and disk growth | Checkpoint pressure and WAL lag stay bounded; disk growth matches the profile in §1 | Long sustained write load; watch `pg_stat_bgwriter`, WAL generation rate, and `pg_database_size` |
| C7 | Slow-query and bloat drift under load | No unbounded growth in the slow-query set | `pg_stat_statements` over the run; compare to a baseline snapshot |

`V38`-style index migrations take `SHARE` locks that block writes while they build — rehearse C2 alongside a concurrent index migration if a release ships one.

## 3. Dependency-failure cases

Each graded on one question: does the platform **degrade** in the documented way, or **fail closed**? Silent degradation is the failure mode to look for.

| # | Dependency removed | Expected behaviour | Evidence |
| --- | --- | --- | --- |
| D1 | Supabase Auth (GoTrue) | New sign-ins stop; the api keeps serving existing valid tokens until the cached key set expires. Fail closed on anything it cannot validate | [incident-runbook.md](incident-runbook.md) §5.6; `/actuator/health/readiness` |
| D2 | Supabase Storage | Document upload/download degrades; ledger, reconciliation, workflow, and reports keep serving — the api reads no `SUPABASE_*` variables (#340) | incident-runbook §5.6 |
| D3 | Email / SMTP | Invites and notifications queue or fail visibly; nothing else degrades | Supabase project logs |
| D4 | Redis | Rate limiting keeps enforcing with in-process counters, never fails open, one warning per 30 s; quotas become per-replica | deploy/README "Rate limiting"; incident-runbook §5.7 |
| D5 | Neo4j | Graph-backed features degrade; the ledger of record is unaffected (the graph is a projection, ADR-0004) | readiness components |
| D6 | An external vendor provider | Ingestion for that source stalls; the cursor does not advance past the gap and no partial row is promoted | modules/ingestion adapters |
| D7 | A second api replica (so Redis quota is shared) | A tenant's quota is enforced across replicas, not per replica | deploy/README "Rate limiting" |

## 4. Resource and restart limits

The compose already bounds each service (`deploy/dokploy.compose.yml`): api 2 CPU / 2 GB, agents 1 / 1 GB, web 0.5 / 256 MB, redis 0.25 / 96 MB, otel-collector 0.25 / 256 MB, with `restart: unless-stopped` and per-service healthchecks. The tests here establish the **observable consequence** of those bounds — what a caller sees at the limit — not that the numbers are configured. Exceeding a memory limit must surface as a restart with a visible healthcheck gap, never as silent request loss.

## 5. Running a test

- **Environment:** staging only. A capacity test against production is a load incident; ADR-0002's topology exists so this never has to happen.
- **Baseline first:** capture the idle-state metrics so a result can be attributed to load rather than drift.
- **One variable at a time.** Concurrent tests cannot say which dimension caused the failure.
- **Record it** on [drill-evidence-template.md](drill-evidence-template.md): the profile from §1, which cases ran, the threshold each was graded against, the measured value, and pass/fail. A test without a stated expectation cannot pass.
- **Follow-ups** become [backlog-tracker.md](backlog-tracker.md) entries linking their source.

## 6. What this does not cover

- **Load-test tooling does not exist in this repo yet.** No k6, Gatling, or JMeter profile is committed; choosing one is part of doing this work, and `docs/reliability.md` §6 lists load testing as out of scope for the reliability document itself.
- **Threat modelling and abuse load** — the per-IP and per-tenant limits in `deploy/README.md` are boundary controls, not a capacity case.
- **Alerting on the results.** Nothing evaluates these thresholds in production until a backend sits behind `OTELCOL_EXPORT=otlphttp` ([incident-runbook.md](incident-runbook.md) §8, [reliability.md](reliability.md) §5 item 8).
- **High availability.** Docker Compose gives no database failover; ADR-0002 requires the failover strategy to be documented and tested separately, and it is not covered here.
