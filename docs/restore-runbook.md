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
| PostgreSQL | `octo-supabase-db` (dokploy-network) | `octo` domain schema, `auth` (GoTrue), `storage` metadata, `flyway_schema_history` | nightly `pg_basebackup` plus a continuous WAL archive, gzipped to `/backups/octo/production/` on the host (§2.1) |
| Neo4j | `octo-neo4j-db` (dokploy-network) | graph projections from the ontology schema | `neo4j-admin database dump` to object storage |
| Supabase Storage | `octo-supabase` project | uploaded documents | storage bucket sync / S3-compatible copy |

**Deployed state (observed 2026-10-09, #622):** the Postgres backup layer is
**provisioned on both production and staging**. `archive_mode = on` with
`archive_timeout = 300s`, `pg_stat_archiver.failed_count = 0` and
`archived_count` climbing, plus a nightly base backup. A point-in-time restore
has been rehearsed end to end from these backups on both environments —
measured RPO ~3.7 min on production, ~85 s on staging, against the 15-minute
target. §2.1 records the configuration; §4 is executable again.

Before this, `octo-supabase-db` ran with `archive_mode = off` and zero archived
WALs since the container was created (2026-09-26), so no restore was possible
at all. That gap, and the findings it surfaced, are on #622.

**Not provisioned (as of 2026-10-09):** two gaps remain. The base backup runs
nightly but its **last success is not monitored** — nothing alerts if archiving
stalls or the cron stops, which is the failure §3's first item exists to catch.
And the backup copies are **not encrypted**; they sit beside the database in
plain gzip. Both are open on #622.

### 2.1 How the Postgres backup is provisioned

`supabase/postgres:17.6.1.136` ships `pg_basebackup`, `curl`, `wget`, `tar` and
`gzip` — but **no `pgbackrest`, no `wal-g`, no `aws`, no `rclone`**. The
mechanism therefore uses only what the image carries. Both environments run the
same configuration, separated by path: production under
`/backups/octo/production/`, staging under `/backups/octo/staging/`.

| | |
| --- | --- |
| `archive_mode` | `on` — requires a **restart**, not a reload |
| `archive_timeout` | `300s`, which bounds RPO at 5 minutes |
| `archive_command` | gzips each segment to `/backups/wal/%f.gz`, written atomically via `.tmp` then `mv` |
| Base backup | nightly `pg_basebackup -D /backups/base/<UTC> -Ft -z`, last 7 retained |
| Retention | WAL kept 14 days, deliberately outliving the oldest base backup so no PITR window breaks |

Three details are load-bearing and should not be "optimised" away:

- **gzip.** A WAL segment is 16 MB even when it closed early and is nearly
  empty; the [PostgreSQL docs](https://www.postgresql.org/docs/17/runtime-config-wal.html#GUC-ARCHIVE-TIMEOUT)
  call this out directly. Uncompressed at 300s that is roughly 4.6 GB/day;
  gzipped it is roughly 3 to 15 MB/day, with near-empty segments around 19 KB.
- **The `hba_file` override.** `pg_basebackup` opens a *replication*
  connection, and the image's `pg_hba.conf` has no `replication` record — its
  own comment notes that `all` does not match `replication`, so the loopback
  trust rule does not cover it. A copy carrying the base rules plus two
  local-only replication records lives at `/backups/octo/<env>/pg_hba.conf`,
  and the `db` service points `hba_file` at it. Editing
  `/etc/postgresql/pg_hba.conf` inside the container would be lost on the next
  redeploy, because that path is not on a volume.
- **The staging and production paths are separate.** Getting them crossed would
  have one environment archiving into the other's tree.

Verify at any time:

```sql
show archive_mode;
show archive_timeout;
select archived_count, last_archived_time, failed_count, last_failed_time
  from pg_stat_archiver;
```

`failed_count` above zero means WAL is accumulating on the primary and the disk
will fill. Turn `archive_mode` off and correct the command rather than leaving it
running.

All three are separate failure domains. A full platform restore restores all of
them; a surgical restore may only need one.

## 3. Pre-flight (do once, verify quarterly)

Items 1 and 4 are **open on production today** — see §2's deployed-state notes;
they record what must exist, not what does. Item 2 now passes on both
environments. Item 5 holds on staging; production has no smoke tenant, since
the synthetic account was created for the staging rehearsal.

- [ ] Base backup job runs daily and its last success is monitored — an
      unmonitored backup silently stopped is the standard failure this catches.
- [ ] WAL archive stream is continuous (`archive_status` shows no failed WALs).
- [ ] `flyway_schema_history` is inside the backup (it is — same database).
- [ ] Encryption keys for the backup copies are in the secret manager, not on
      the DB host.
- [ ] The smoke tenant + user (`octo-ops-smoke@test.invalid` per deploy/README)
      exist for post-restore verification.

## 4. Postgres point-in-time restore

**Executable.** There is no `pgbackrest` or `wal-g` in the deployed image, so the
restore is the inverse of §2.1's provisioning: unroll the base backup, then let
`restore_command` pull segments from the archive. **Never restore over the live
volume** — work in a scratch container, as `deploy/drill/restore-drill.sh` does.

```bash
# 1. A scratch container with the backup tree mounted. Same image, so the
#    container's postgres uid (100) already matches the backup files' owner.
docker run -d --name octo-restore \
  -v /backups/octo/production:/backups \
  --entrypoint sleep supabase/postgres:17.6.1.136 infinity

# 2. Unroll the base backup and the WAL it shipped with. Both archives matter:
#    base.tar.gz is the database, pg_wal.tar.gz is the WAL written during the
#    backup, which recovery needs to reach a consistent state.
docker exec octo-restore sh -c '
  mkdir -p /restore/data &&
  tar -xzf /backups/base/<backup>/base.tar.gz -C /restore/data &&
  mkdir -p /restore/data/pg_wal &&
  tar -xzf /backups/base/<backup>/pg_wal.tar.gz -C /restore/data/pg_wal &&
  chown -R postgres:postgres /restore &&
  chmod 700 /restore/data'

# 3. Point recovery at the archive. restore_command is the exact inverse of
#    archive_command. Add recovery_target_time for a targeted recovery.
cat > /tmp/auto.conf <<'EOF'
restore_command = 'gunzip -c /backups/wal/%f.gz > %p'
recovery_target_action = 'promote'
EOF
docker cp /tmp/auto.conf octo-restore:/restore/data/postgresql.auto.conf
docker exec octo-restore sh -c 'touch /restore/data/recovery.signal &&
  chown postgres:postgres /restore/data/postgresql.auto.conf &&
  chmod 700 /restore/data'

# 4. Start it and watch the replay.
docker exec -d -u postgres octo-restore sh -c \
  'postgres -D /restore/data > /tmp/restore.log 2>&1'
docker exec octo-restore tail -20 /tmp/restore.log
```

Expect `consistent recovery state reached`, `redo done at …`, and
`database system is ready to accept connections`.

**`gunzip: … No such file or directory` in the log is expected, not an error.**
Recovery asks for the next segment, the archive has run out, and
`restore_command` correctly returns non-zero — which recovery reads as
"end of archive".

`chmod 700 /restore/data` is required: Postgres refuses a data directory that
others can read. `mkdir` and `tar` do not set that for you.

Without a `recovery_target_time` the instance recovers to the newest segment in
the archive, so it lands up to `archive_timeout` behind the primary. For a
targeted recovery, choose the last WAL commit before the damage — restoring past
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
