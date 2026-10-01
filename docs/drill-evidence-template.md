# Drill evidence template

The record format for every OCTO rehearsal — restore, upgrade/rollback, and capacity or dependency-failure — copied per drill into a record under the incident/drill thread. Owner: Platform / DevOps. A drill that is not recorded here did not happen.

**A drill record is not evidence unless it records the measured values and links the artifacts.** "The restore worked" is a claim; `RPO 6 min 12 s, RTO 1 h 52 min 12 s, restore log and checklist output linked` is evidence. ADR-0002's acceptance boxes are ticked only from a filled record produced on **staging** — never from documentation, a plan, or a local harness run (`deploy/drill/lib.sh` says the same in its header: a local run is preparation, never acceptance). `deploy/drill/lib.sh`'s `evidence_header` mints the header fields below in this order, so a harness-generated record drops straight into this template; its rule covers every field — **ids and timings only, never data.**

## 1. Header

| Field | Value |
| --- | --- |
| Drill id | `YYYYMMDDTHHMMSSZ-<kind>` — the id format `evidence_header` mints |
| Type | restore · upgrade/rollback · capacity/dependency-failure |
| Environment | `staging` is the only one whose result ticks an ADR-0002 box |
| Date and time (UTC) | start … end |
| Operator | |
| Reviewer | not the operator (§6) |
| Repo commit | short sha + full sha of the tree under test |
| Container images under test | exact tag **and** digest per image: `octo-api`, `octo-web`, `octo-agents`, the pinned Supabase release, PostgreSQL, Neo4j, Redis, otel-collector. Tags carry the commit sha (`main-cf5c269`); `:latest` makes the drill unreproducible and cannot be lifted into a rollback |
| Artifacts and procedure | links to logs, query output, backup manifest, alarms observed; path + version of the procedure under test |

## 2. Preconditions — all true before the clock starts

- [ ] The target environment is staging, isolated from production data; production data is never used in test without formal approval and irreversible masking.
- [ ] A base backup exists and its **last success** is verified — not merely scheduled.
- [ ] WAL archiving is continuous (`archive_status` shows no failed WALs).
- [ ] Encryption keys for the backup copies are in the secret manager, not on the DB host.
- [ ] The smoke tenant and user (`octo-ops-smoke@test.invalid`) exist for verification.
- [ ] The previous rehearsal's open follow-ups are closed or explicitly accepted.
- [ ] Upgrade/rollback drill: the rollback path is written down (AGENTS.md requires a tested rollback for T2) and no migration will be reverted — migrations are forward-only.
- [ ] Capacity drill: the load profile is stated first — users, ingest throughput, storage growth, query concurrency, reporting peak (ADR-0002, "Availability and scaling").

## 3. Results

One row per step of the procedure under test. `Expected` is what the procedure claims and is written down **before** the step runs; a step with no stated expectation cannot pass.

| # | Step (procedure §) | Expected result | Observed result | Pass/Fail | Notes |
| --- | --- | --- | --- | --- | --- |
| 1 | | | | | |
| 2 | | | | | |

For a restore drill the expectation column is restore-runbook §7's checklist verbatim: `flyway_schema_history` verifies (no failed rows, no missing versions); audit chain verification passes (`verifyAuditChain`); a login plus one tenant-scoped read per role succeeds end to end; the latest `seq` per append-only table is monotonic against pre-loss metrics; the incident doc records the loss window, target time, and verification evidence.

## 4. Measurements

| Metric | Target | Measured | Derivation |
| --- | --- | --- | --- |
| RPO | ≤ 15 min (restore-runbook §1) | | |
| RTO | ≤ 4 h (restore-runbook §1) | | |
| Rehearsal cadence | quarterly, logged | | date of the previous record |

Show the arithmetic; do not assert the number.

- **RPO** = (time of the failure, or of the last write before it) − (timestamp of the newest **recovered** committed row). Take both timestamps from one clock and say which: audit `recorded_at` comes from the database clock (V6).
- **RTO** = (`/actuator/health/readiness` healthy **and** the restore-runbook §7 checklist passed) − (declared start of the drill). The Flyway boot and the readiness wait are part of the restore and belong inside the measurement.
- A miss is recorded as a miss: the value, the overshoot, and the step that consumed the time. An overrun with a named cause is a usable record; a rounded-up claim is not.

## 5. Deviations and follow-ups

Anything that did not go as the procedure says, including steps the operator had to invent — those are procedure defects, not operator error.

| # | Deviation | Impact on the result, RPO, or RTO | Follow-up | Owner | Link (backlog entry) |
| --- | --- | --- | --- | --- | --- |
| 1 | | | | | |

## 6. Sign-off

| Role | Name | Decision | Date (UTC) |
| --- | --- | --- | --- |
| Operator | | drill completed as recorded | |
| Reviewer | | record accepted / rejected | |

A rejected record is corrected and re-reviewed, not annotated in place. The reviewer ticks the ADR-0002 acceptance boxes from this record and links it from reliability.md §4 — never from the existence of a procedure.

## 7. Illustrative example — **not evidence**

> **Nothing below happened.** The figures are invented to show the arithmetic and the level of detail a real record needs. Do not cite it, do not copy its numbers into ADR-0002, and do not read it as a rehearsed restore.

```text
Drill id:      20261014T090500Z-restore   Type: restore (PostgreSQL point-in-time)   Env: staging
Started (UTC): 2026-10-14T09:05:00Z       Ended (UTC): 2026-10-14T10:57:12Z
Operator:      sre-on-duty                Reviewer: platform-owner
Repo commit:   9f3c1ab (9f3c1ab4d0e5c7a2b1f8e6d4c3a2918077b6e5d4)
Images:        octo-api:main-9f3c1ab@sha256:1111…   postgres:17-alpine@sha256:2222…
Artifacts:     drill-20261014/{restore.log, verify.txt, wal-lag.txt}
```

Preconditions verified: base backup `2026-10-14T02:00Z` present; WAL archive continuous to `09:03Z`; backup keys in the secret manager; `octo-ops-smoke@test.invalid` present.

| # | Step (restore-runbook §) | Expected | Observed | Pass | Notes |
| --- | --- | --- | --- | --- | --- |
| 1 | §4 restore to target 09:00:00Z | restore completes | completed 09:41 | ✅ | 38 min for a 42 GB cluster |
| 2 | §4.1 `flyway_schema_history` | `bool_and(success)` true, no gaps | rank 41, all true | ✅ | |
| 3 | §4.3 readiness | healthy | healthy after 1 m 58 s | ✅ | Flyway validated history on boot |
| 4 | §7 audit chain | `verifyAuditChain` returns null | null | ✅ | 12 407 rows read |
| 5 | §7 monotonic seq | latest `ledger_event.seq` ≥ pre-loss | equal | ✅ | |
| 6 | §7 login + tenant read | one read per role | 3 roles OK | ✅ | smoke user |

```text
RPO  target ≤ 15 min   measured 6 min 12 s
     last write before loss   09:00:00Z   (database clock)   newest recovered row  08:53:48Z
     09:00:00 − 08:53:48 = 00:06:12   → within target

RTO  target ≤ 4 h      measured 1 h 52 min 12 s
     declared start  09:05:00Z   readiness healthy + §7 checklist passed  10:57:12Z
     10:57:12 − 09:05:00 = 01:52:12   → within target
```

Deviation: the pooler was repointed before the detached verification finished, costing ~7 min and relying on operator memory. Follow-up: put the verification gate ahead of the repoint in restore-runbook §4 (owner: sre-on-duty; backlog entry linked).

Sign-off: operator `sre-on-duty` — completed as recorded, 2026-10-14. Reviewer `platform-owner` — accepted, RPO and RTO within target, and **no ADR-0002 box is ticked from this example, because it is an example.**
