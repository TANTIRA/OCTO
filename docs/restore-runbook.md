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

## 2. What lives where

| Store | Service | Contents | Backup layer |
| --- | --- | --- | --- |
| PostgreSQL | `octo-supabase-db` (dokploy-network) | `octo` domain schema, `auth` (GoTrue), `storage` metadata, `flyway_schema_history` | base backup + WAL archive (pgBackRest or wal-g on the DB host) |
| Neo4j | `octo-neo4j-db` (dokploy-network) | graph projections from the ontology schema | `neo4j-admin database dump` to object storage |
| Supabase Storage | `octo-supabase` project | uploaded documents | storage bucket sync / S3-compatible copy |

All three are separate failure domains. A full platform restore restores all of
them; a surgical restore may only need one.

## 3. Pre-flight (do once, verify quarterly)

- [ ] Base backup job runs daily and its last success is monitored — an
      unmonitored backup silently stopped is the standard failure this catches.
- [ ] WAL archive stream is continuous (`archive_status` shows no failed WALs).
- [ ] `flyway_schema_history` is inside the backup (it is — same database).
- [ ] Encryption keys for the backup copies are in the secret manager, not on
      the DB host.
- [ ] The smoke tenant + user (`octo-ops-smoke@test.invalid` per deploy/README)
      exist for post-restore verification.

## 4. Postgres point-in-time restore

```bash
# On the DB host (or a recovery host — never over the live volume):
pgbackrest --stanza=octo restore --type=time --target="YYYY-MM-DD HH:MM:SS+07" \
  --pg1-path=/var/lib/postgresql/data
# or wal-g equivalent for the same stanza
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

## 6. Neo4j restore

```bash
docker exec octo-neo4j-db neo4j-admin database dump neo4j --to-path=/backups
# restore:
docker exec octo-neo4j-db neo4j-admin database load neo4j --from-path=/backups --overwrite-destination
```

The graph is a projection — after a Postgres restore, diff `graph_node_id`
back-references against restored assets; rows the projection missed rebuild on
the next ingestion pass, but a `graph_node_id` dangling against a missing graph
node must be reconciled before the API serves it.

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
