# PR E — restore procedure and evidence record

**Title:** `docs(ops): RPO/RTO measurement method and drill evidence template (#307)`
**Branch:** `docs/307-restore-procedure`
**Risk tier:** T1 — documentation only.

## Situation

`docs/restore-runbook.md` states RPO ≤ 15 min and RTO ≤ 4 h, and `deploy/drill/restore-drill.sh` (PR B) grades a rehearsal against them. But nothing said how either value is *measured*.

## Complication

An unmeasured drill cannot fail, so it cannot pass either. Left to operator judgement, the same restore can be reported as within target or not depending on which timestamps were picked and which clock they came from — and audit `recorded_at` comes from the database clock while log timestamps do not.

## Question

What has to be fixed so a rehearsal produces a number that means something?

## Answer

`docs/restore-runbook.md` §1 gains the arithmetic:

- **RPO** = (commit time of the last write before the loss, or the loss event) − (commit time of the newest committed row present after the restore), both timestamps from **one clock**, named. With continuous archiving the recovery point is whatever committed last before the chosen target.
- **RTO** = (readiness healthy **and** the §7 checklist passed) − (declared start of the drill). Flyway's boot-time validation belongs inside the number, as does repointing the application.
- Misses are recorded as misses: the value, the overshoot, and the step that consumed the time.

`docs/drill-evidence-template.md` is the record format those measurements land in: header (drill id, environment, commit, exact image tags and digests), preconditions, a per-step results table where the expectation is written **before** the step runs, the measurement block with the arithmetic shown, deviations, and sign-off — plus a worked example clearly marked as illustrative and explicitly not evidence.

## Test evidence

`deploy/drill/selftest.sh` (PR C) asserts both documents exist, contain no `FAIL` row, declare RPO and RTO, and state that a local run is not staging evidence. It also checks every link resolves from its source file.

## Impact

Two documents. No code.

## Rollback

Revert the commit. Nothing depends on these at runtime.

## Reviewer notes

The RPO/RTO **values** are not new here — they were already in §1. What is new is how they are measured. Both remain launch-blocking decisions under `ADR-0002:125` and still need the owner's confirmation, which is recorded in the ADR itself (PR F).
