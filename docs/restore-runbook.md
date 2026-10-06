# Restore runbook — PostgreSQL, Neo4j, object storage

How to recover OCTO's datastores after loss or corruption. Owner: Platform / DevOps
(reliability.md §5 item 4). Backups and PITR are operator-provided infrastructure
(ADR-0002) — this runbook is the procedure the repo commits to. **T2 by nature:**
a restore writes financial data; rehearse it on staging before ever needing it.

Read this before touching anything: the `octo` schema is append-only event logs
(`ledger_event`, `prospect_event`, `tenant_member_event`, `workflow_task_event`).
Nothing regenerates them — there is no upstream to replay from. A database lost
beyond backup recovery is lost, period, which is why the rehearsal requirement
exists.

## 1. Targets

| Metric | Target | Basis |
| --- | --- | --- |
| RPO | ≤ 15 min | WAL archive interval; tighter than any SLO data-durability promise (99.95% committed writes) |
| RTO | ≤ 4 h | One working session to fail over; quarterly review hours an outage starts in are not fixable, the 4 h ceiling is |
| Rehearsal | quarterly, logged | reliability.md §4: an unrehearsed runbook is a missing runbook |

### How RPO and RTO are measured

A drill that does not measure these has not tested anything, so the arithmetic is fixed here rather than left to the operator. The record format is [drill-evidence-template.md](drill-evidence-template.md); `deploy/drill/restore-drill.sh` performs a local rehearsal and writes one.

- **RPO** = (commit time of the last write before the loss, or the loss event itself) − (commit time of the newest committed row present after the restore). Take both timestamps from **one clock** and say which: audit `recorded_at` and `occurred_at` come from the database clock (V6), so use `recorded_at` for both sides. With continuous archiving the recovery point is whatever committed last before the chosen target, so the measured RPO is how far back that point sits — bounded above by the archive interval.
- **RTO** = (readiness healthy **and** the §7 checklist passed) − (declared start of the drill). Flyway's boot-time validation at step 3 is part of the restore and belongs inside the number, as does repointing the application.
- **Report the number you measured, and the overshoot if you miss.** A 6-hour RTO with a named cause is a usable record; a rounded-up claim is not.

`docs/reliability.md` §4 records that no restore has been rehearsed. That stays true until a filled record exists, and the ADR-0002 acceptance box is ticked from that record — never from this procedure existing.

## 2. What lives where

| Store | Service | Contents | Backup layer |
| --- | --- | --- | --- |
| PostgreSQL | `octo-supabase-db` (dokploy-network) | `octo` domain schema, `auth` (GoTrue), `storage` metadata, `flyway_schema_history` | base backup + WAL archive (pgBackRest or wal-g on the DB host) |
| Neo4j | `octo-neo4j-db` (dokploy-network) | graph projections from the ontology schema | `neo4j-admin database dump` to object storage |
| Supabase Storage | `octo-supabase` project | uploaded documents | storage bucket sync / S3-compatible copy |

**Deployed state (observed 2026-10-01, #301):** `octo-supabase-db` runs with
`archive_mode = off`, no `archive_command`, and `pg_stat_archiver` shows zero
archived WALs since the container was created (2026-09-26). The Postgres backup
layer in the table above is therefore **not provisioned**: §3's WAL item cannot
pass, §4 has no stanza or archive to restore from, and the ≤ 15 min RPO is
unreachable until the operator provisions base backup + WAL archiving
(ADR-0002). Until then the only recovery path is a logical dump from
`deploy/backup.sh` (manual; plain SQL, restored with `gunzip -c <archive> | psql`;
RPO = time since the last dump, no PITR). Dokploy volume snapshots are not a
path yet: the Backups and Schedule tabs were empty when checked, still to be
re-confirmed on the `octo-supabase-db` project itself. Re-check
with `show archive_mode;` and `select * from pg_stat_archiver;` before a drill.

**The deployed image also lacks the tools §4 names (observed 2026-10-04, #301).**
`octo-supabase-db` runs `supabase/postgres:17.6.1.136`, which ships
`pg_basebackup`, `curl`, `wget`, `tar`, `gzip` — but **no `pgbackrest`, no
`wal-g`, no `aws`, no `rclone`**. So §4 fails for a second, independent reason:
the restore tool does not exist on the DB host, not only because there is no
archive. The mechanism choice is open (#301): (A) `archive_command` copies WAL
to a mounted volume and a host-side job ships it to object storage — stock
image, no build; (B) a custom image `FROM supabase/postgres` with wal-g —
off-host, at the cost of maintaining an image that diverges from the Dokploy
template; (C) a `pg_receivewal` sidecar streaming to object storage — off-host,
at the cost of another container. `pg_basebackup` covers the base-backup leg
under all three. Until the choice lands, treat §4 as **blocked procedure**,
not an executable one.

All three are separate failure domains. A full platform restore restores all of
them; a surgical restore may only need one.

## 3. Pre-flight (do once, verify quarterly)

The first two items are **open on production today** — see §2's deployed-state
notes; they record what must exist, not what does.

- [ ] Base backup job runs daily and its last success is monitored — an
      unmonitored backup silently stopped is the standard failure this catches.
- [ ] WAL archive stream is continuous (`archive_status` shows no failed WALs).
- [ ] `flyway_schema_history` is inside the backup (it is — same database).
- [ ] Encryption keys for the backup copies are in the secret manager, not on
      the DB host.
- [ ] The smoke tenant + user (`octo-ops-smoke@test.invalid` per deploy/README)
      exist for post-restore verification.

## 4. Postgres point-in-time restore

**Blocked on §2's two findings.** There is no archive to restore from
(`archive_mode = off`), and the tool named below is not in the deployed image —
so this section is the *target* procedure, not an executable one, until the
operator provisions base backup + WAL archiving under one of the options in §2.
If data is lost before then, the only recovery path is the last `deploy/backup.sh`
logical dump, restored with `gunzip -c <archive> | psql` — no PITR.

Once a base backup and archive exist:

```bash
# On the DB host (or a recovery host — never over the live volume):
pgbackrest --stanza=octo restore --type=time --target="YYYY-MM-DD HH:MM:SS+07" \
  --pg1-path=/var/lib/postgresql/data
# or the wal-g equivalent; under option A the equivalent is pg_basebackup +
# recovery_target_time replay from the archived WAL shipped off-host
```

Choose the target time as the last WAL commit before the damage — restoring past
it re-applies the corrupting transaction too.

Then:

1. Start the restored Postgres **detached from the app** (dokploy-network off, or
   a scratch compose project) and verify:
   ```sql
   select max(installed_rank), bool_and(success) from flyway_schema_history;
   select count(*) from octo.ledger_event;
   select count(*) from octo.tenant_member_event;
   ```
   Row counts should match the last pre-loss metrics; `bool_and(success)` must be true.
2. Repoint the pooler/api at the restored service (compose env `POSTGRES_HOST`),
   restart the app project (`VPS_restartProjectV1` — env changes do not recreate
   containers, deploy/README gotcha).
3. Confirm `/actuator/health/readiness` goes healthy — Flyway validates the
   history on boot and would fail it loudly.

## 5. Surgical table restore

For "one table/one tenant damaged" — never roll the whole database back:

```bash
pg_dump --host=<restored> -U postgres -d postgres -n octo \
  -t octo.prospect -t octo.prospect_event --data-only -f /tmp/prospect.sql
```

Load the rows onto live with `psql -f`. The append-only schema means *insert*
corrections are additive; re-inserting rows that already exist conflicts on PK —
filter by id/seq rather than truncate-and-reload. Tables guarded by
`reject_mutation` triggers (V1, V7) can never receive UPDATE/DELETE — corrupted
append-only rows are corrected by superseding rows, matching the domain model.

**Applied migrations are never rolled back in place.** For V5–V7 specifically a
pre-V5 state is not a recovery target: it drops every `workflow_task` and
`workflow_task_event`, the entire `audit_event` hash chain, and the IC/report
approvals recorded on top of them, while leaving dangling `task_id` references
in V13–V19 rows — and the V5–V7 files cannot be inverted as written because
V28, V37 and V43 have since moved and replaced their objects (#302). A bad
applied migration is corrected by a forward migration; restore-from-backup is
reserved for data loss.

## 6. Neo4j backup and restore

The graph store (`octo-neo4j-db`, its own Dokploy project per `infra/README.md`)
is a **projection**, not the ledger of record: PostgreSQL's `octo` schema stays
authoritative even if the graph is lost, and the graph is reconstructable from
ledger + staging by replaying ingestion (ADR-0004, "Neutralized risks"). That
makes a Neo4j restore lower-stakes than §4's Postgres restore, but still the
documented path — see ADR-0004's acceptance criterion for this runbook.

### 6.1 Pre-flight (do once, verify quarterly)

- [ ] `neo4j-data` volume snapshots run on the schedule stated in
      `infra/README.md` ("Data" row) and the last snapshot's success is
      monitored — the same "unmonitored backup silently stopped" failure §3
      guards against for Postgres.
- [ ] The pinned image tag (`neo4j:2025.12.1-community` as of this writing —
      `Neo4jSchemaIT`/`deploy/dokploy.compose.yml` carry the current value) is
      recorded before any dump/restore: `neo4j-admin database load` across a
      version mismatch can fail or silently reformat the store.
- [ ] `ontology/octo-investment.cypher` (the schema under `Neo4jSchemaIT`) is
      at the commit the dump was taken against — a restore onto a newer schema
      needs the intervening constraint/index changes re-applied by hand.

### 6.2 Backup

```bash
docker exec octo-neo4j-db neo4j-admin database dump neo4j --to-path=/backups
```

Copy `/backups` off the container host the same way the Postgres base backup
leaves `octo-supabase-db` (§2's "backup layer" column) — a dump that lives only
on the volume it protects against is not a backup.

### 6.3 Restore

```bash
docker exec octo-neo4j-db neo4j-admin database load neo4j --from-path=/backups --overwrite-destination
```

`--overwrite-destination` replaces the live graph — there is no partial/table-scoped
Neo4j restore equivalent to §5's surgical Postgres path; a graph restore is
always whole-database. The container must be stopped for the duration of the
load (`neo4j-admin database load` does not run against a live store) — expect
the graph to be unavailable for the restore's duration, unlike Postgres's
detached-recovery-host approach in §4.

### 6.4 Post-restore verification

- [ ] `Neo4jSchemaIT`-equivalent constraint check: every constraint/index
      `ontology/octo-investment.cypher` declares exists on the restored store
      (`SHOW CONSTRAINTS`, `SHOW INDEXES`).
- [ ] Diff `graph_node_id` back-references in Postgres against the restored
      graph: a `graph_node_id` whose node the restore does not contain must be
      reconciled (re-ingested or cleared) before the API serves it. Rows the
      restored graph is simply missing (dump taken before they were written)
      rebuild on the next ingestion pass — that is the expected, low-severity
      case; a dangling reference pointing at a node that was deleted and
      replaced is the one that needs attention.
- [ ] A look-through query against a known multi-level hierarchy (see the
      perf test added for ADR-0004's last acceptance box) returns the same
      shape as pre-loss — a restore that silently drops a relationship type
      passes a row-count check but fails this one.

## 7. Post-restore checklist

- [ ] `flyway_schema_history` verifies (no failed rows, no missing versions)
- [ ] Audit chain verification passes (`verifyAuditChain`, V6)
- [ ] A login + one tenant-scoped read per role succeeds end-to-end
- [ ] Latest `seq` per append-only table is monotonic vs. pre-loss metrics
- [ ] Incident doc records: loss window, restore target time, verification evidence
- [ ] The rehearsal/restore evidence lands back on reliability.md §4

## 8. What this does not cover

Supabase auth user recreation (GoTrue rows restore with `auth` schema — no separate
path needed), local dev data (unprotected by design), and any datastore owned by a
client-managed deployment (their contract's responsibility, per ADR-0002).
