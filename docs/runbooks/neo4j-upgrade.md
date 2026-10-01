# Upgrade runbook — Neo4j graph store

How to move the pinned Neo4j image forward, and how to get back if it goes
wrong. Owner: Platform / DevOps. **T2** ([AGENTS.md](../../AGENTS.md)) — the
graph store backs ontology-derived reads the API serves, even though
PostgreSQL stays the ledger of record.

This is the "Neo4j backup/restore and upgrade runbooks exist" acceptance box
on [ADR-0004](../adr/0004-neo4j-graph-store.md). [`docs/upgrade-runbook.md`](../upgrade-runbook.md)
§7 named this runbook as pending; it now exists, following the same shape —
rehearse the rollback path first, record what was true before touching
anything, verify after.

## 1. What moves

| Layer | What it is | How it moves |
| --- | --- | --- |
| Neo4j image | `neo4j:2025.12.1-community` pinned in `deploy/dokploy.compose.yml` (the api's own compose comment) and in the separate `octo-neo4j-db` Dokploy project (`infra/README.md`) | Bump the tag in both places — the api repo's reference and the Neo4j project's own compose — and redeploy the Neo4j project first, then restart the app project so the driver reconnects against the new version |
| Graph schema | `ontology/octo-investment.cypher`, applied by whatever process seeds/updates constraints — see `Neo4jSchemaIT` for how CI validates it against a live instance | A schema change ships as a normal PR; `Neo4jSchemaIT` is the CI gate (ADR-0004's first, already-checked acceptance box) |

Unlike the Supabase stack, Neo4j here is **one external service**, not a
template with Kong/Studio/auth layers — the upgrade surface is narrower: an
image tag and the schema file.

## 2. Preconditions

- [ ] Target environment is staging first — same requirement as
      [upgrade-runbook.md](../upgrade-runbook.md) §2; an upgrade rehearsed only in
      production is not rehearsed.
- [ ] A verified Neo4j dump exists per [restore-runbook.md](../restore-runbook.md)
      §6.2 — "a backup you have not restored is not a verified backup" applies
      here exactly as it does to Postgres.
- [ ] The target image's release notes are read for breaking changes to the
      Cypher dialect, constraint syntax, or the dump/load format —
      `ontology/octo-investment.cypher`'s constraints are version-sensitive.
- [ ] The current and target image tags are recorded in the drill record
      ([drill-evidence-template.md](../drill-evidence-template.md) already lists
      Neo4j among the tracked images) — this is what makes the rollback
      nameable.

## 3. Upgrade procedure

1. **Rehearse the rollback path first.** Take a dump per
   [restore-runbook.md](../restore-runbook.md) §6.2 against the pre-upgrade
   version, and confirm it loads cleanly on a scratch container running the
   **same** pre-upgrade image tag — proving the dump is usable before the
   version that produced it is gone.
2. **Apply the upgrade on staging.** Bump the image tag in the `octo-neo4j-db`
   project's own compose and redeploy that project — the api repo does not
   drive this deploy, only documents the pinned version it expects.
3. **Verify the schema survived.** Run the `Neo4jSchemaIT` checks (or the
   `SHOW CONSTRAINTS`/`SHOW INDEXES` queries from
   [restore-runbook.md](../restore-runbook.md) §6.4) against the upgraded
   instance before pointing the api at it.
4. **Restart the app project** so the bolt driver reconnects — env/image
   changes on the Neo4j project do not recreate the api's containers
   (the same "env changes do not recreate containers" gotcha
   [upgrade-runbook.md](../upgrade-runbook.md) §3.6 documents for Postgres).
5. **Verify:**
   ```bash
   curl -sf https://api-octo.mesta.click/actuator/health/readiness   # includes any graph-read dependency check
   ```
   Then run one look-through query exercising a known multi-level hierarchy
   and confirm the result shape matches pre-upgrade.
6. **Record the result** on [drill-evidence-template.md](../drill-evidence-template.md),
   including the before/after image tags and digests.

## 4. Rollback

Neo4j has no in-place schema rollback equivalent to Flyway's forward-only
migrations — a bad upgrade is reverted by redeploying the previous image tag
on the `octo-neo4j-db` project. If the new version wrote data in a format the
old version cannot read (format changes are called out in Neo4j's own release
notes), the dump taken in step 1 is the fallback: restore it per
[restore-runbook.md](../restore-runbook.md) §6.3 onto a container running the
pre-upgrade tag.

| Situation | Rollback |
| --- | --- |
| New tag misbehaves, store format unchanged | Redeploy the previous image tag on the Neo4j project |
| New tag wrote an incompatible store format | Restore the pre-upgrade dump (§2) onto a container running the pre-upgrade tag |

## 5. Rehearsal cadence

Quarterly, and before any Neo4j version bump — same cadence as
[upgrade-runbook.md](../upgrade-runbook.md) §6. Each rehearsal is logged on
[drill-evidence-template.md](../drill-evidence-template.md); a local or staging
rehearsal is preparation, the filled record is what ticks the ADR-0004 box.

## 6. What this does not cover

- The restore procedure itself — [restore-runbook.md](../restore-runbook.md) §6
  owns it.
- Postgres and the Supabase stack's own upgrade mechanics —
  [upgrade-runbook.md](../upgrade-runbook.md) owns those.
- Dual-write atomicity and the graph-ledger reconciliation report — the other
  two open ADR-0004 acceptance boxes; they are a separate design question, not
  an upgrade-procedure gap, and need their own plan before implementation.
