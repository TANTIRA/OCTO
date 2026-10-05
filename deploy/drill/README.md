# deploy/drill — local rehearsal harness

Executable rehearsals for the ADR-0002 acceptance criteria, so the procedures in
[restore-runbook.md](../../docs/restore-runbook.md) and
[upgrade-runbook.md](../../docs/upgrade-runbook.md) are exercised before they are needed
for real. Everything runs against throwaway PostgreSQL containers on the local Docker
daemon and is torn down afterwards.

## What this is for

The repo has procedures and no rehearsals: `docs/reliability.md` §4 records that no restore
has been run, and `deploy/README.md` ("Rollback") that no rollback path has been rehearsed.
This harness closes the part of that gap that can be closed without a staging environment.

## What it proves, and what it does not

| It proves | It cannot prove |
| --- | --- |
| The real migration chain (V1 through the newest migration on disk) applies cleanly to a fresh database | Staging timings, or that production backups exist |
| A base backup plus WAL archive restores to a chosen point in time, with the boundary row present and the post-loss row gone | Real data volume, or the deployed Supabase topology |
| The post-restore checklist passes after a restore: Flyway history, audit chain, append-only controls, role separation | That the operator's environment resembles production |
| The audit chain verification finds a changed hash, a forged canonical, and a deleted middle row | — |
| An upgrade applies without data loss, and a restore returns the schema to its pre-upgrade state | — |

**A local run is preparation, never acceptance.** ADR-0002's acceptance boxes are ticked
only from a filled [drill evidence record](../../docs/drill-evidence-template.md) produced
on staging. Nothing in this directory changes that.

## Running

```bash
deploy/drill/restore-drill.sh                     # writes deploy/drill/evidence/restore-<utc>.md
deploy/drill/rollback-rehearsal.sh                # writes deploy/drill/evidence/rollback-<utc>.md
deploy/drill/selftest.sh                          # audits the harness and its artifacts
FROM_VERSION=41 TO_VERSION=42 deploy/drill/rollback-rehearsal.sh   # a later slice's bump
```

Both exit non-zero when an assertion fails, so a shell chain or CI job can gate on them.
Requires Docker and bash; on Windows use Git Bash. `PG_IMAGE`, `PG_DB`, and `RUNTIME_ROLE`
override the defaults.

## The version window

`rollback-rehearsal.sh` takes `FROM_VERSION` and `TO_VERSION`, and refuses a window its
helpers cannot seed:

- both must be plain digits;
- `TO_VERSION` must be strictly greater than `FROM_VERSION`;
- `FROM_VERSION` must be at or above the **rehearsal floor**, the migration that adds
  `ledger_event.tenant_id`, below which the helper queries reach tables that do not exist;
- `TO_VERSION` may not exceed the newest migration in `db/migrations/`.

Both bounds are derived from `db/migrations/` rather than pinned, so they follow new
migrations without an edit here. `validate_rehearsal_window` in `lib.sh` enforces all four
before any container starts, so an impossible window fails in a second instead of partway
through a run. It runs ahead of the Docker check, so a bad window is reported even when no
daemon is up.

The floor exists because the harness seeds through the *current* schema. Rehearsing below it
would need helpers that speak the pre-V28 `mesta` schema and the pre-V30 ledger shape. That is
a different harness, not a parameter, and it is deliberately out of scope: a pre-V5 state is
not a recovery target, which `docs/restore-runbook.md` and `docs/reliability.md` §5 item 6
record.

## Files

| File | Role |
| --- | --- |
| `lib.sh` | Docker/psql plumbing and the Flyway-equivalent migration applier |
| `restore-drill.sh` | Base backup → WAL → point-in-time restore → §7 checklist, with RPO/RTO |
| `rollback-rehearsal.sh` | Migrate to V(n-1) → back up → apply V(n) → restore → assert pre-upgrade state |
| `selftest.sh` | Audits the harness and its artifacts: verifier behaviour, evidence records, ADR boxes, links |
| `sql/verify-audit-chain.sql` | SQL mirror of `verifyAuditChain`, over the same bytes as V6's canonical form |
| `sql/post-restore-check.sql` | The executed form of restore-runbook §7 |

## The drill checks its own verifier first

`restore-drill.sh` grades a restore with the SQL mirror of `verifyAuditChain`, so before it
touches a backup it asserts that mirror's behaviour: a trigger-written chain must read
`INTACT`, and a changed hash, a hash computed over the wrong canonical, and a missing middle
row must each read `BROKEN`. A verifier that only ever answered `INTACT` would pass every
drill and be worthless. If the self-test fails, the drill refuses to produce an evidence
record at all.

The mirror also anchors the oldest surviving row at genesis, so a log whose first row is not
the genesis row reads `BROKEN` even when every hash is internally consistent. Losing the
*newest* rows stays undetectable from inside the database; that is V6's documented ceiling.

`selftest.sh` extends that to the artifacts around the harness — evidence records, the ADR
acceptance boxes still being open, line endings, executable bits, and every document link
resolving — and is the check to run after changing the harness or the docs.

## The migration applier

`apply_migrations` reproduces what Flyway does, because there is no Flyway Gradle plugin to
call (`build.gradle.kts` explains why) and no JVM assumed on the machine running a drill:

- substitutes `${runtime_role}` with `RUNTIME_ROLE`, which `application.yml` wires from
  `DB_USER` and V3 requires to already exist;
- creates the `octo` schema first and keeps `flyway_schema_history` in it, which is where
  Flyway puts it (`flyway.schemas: octo`) and what V28's `create schema if not exists` expects;
- records each version in the history and skips versions already applied, so a rehearsal can
  migrate to V(n-1) and then V(n) in one database, the way Flyway would.

The audit-chain check is a **SQL mirror** of the Kotlin verifier, not a replacement for it.
The application stays the authority on a live system; the mirror exists because the moment
you need to verify a chain is the moment you have a restored database with no application
attached. It reproduces the byte format exactly — every field length-prefixed
`<utf8 length>:<field>` in column order, epoch-microsecond timestamps, `details` as its
jsonb text form, `sha256(prev_hash || canonical)` from a 32-zero-byte genesis — and it is
tested against the trigger that writes the chain, in both the intact and tampered cases.
