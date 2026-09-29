# Backlog tracker

One list for open engineering and ops items that outlived the PR or doc that
surfaced them. Source of truth for "known but not done": new entries carry a
link back to their source; completed entries are removed, not annotated.

Last triage: 2026-09-29.

## Contributors

| Handle | Area |
| --- | --- |
| [@Venkat5599](https://github.com/Venkat5599) | Finances Engineering |
| [@fikriaf](https://github.com/fikriaf) | AI/ML ops |
| [@mzf11125](https://github.com/mzf11125) | Blockchain Protocol |
| [@Fatihmaull](https://github.com/Fatihmaull) | API Engineering |
| [@daemon-blockint-tech](https://github.com/daemon-blockint-tech) | Fullstack |
| [@Demcruise](https://github.com/Demcruise) | UI/UX |
| [@Aldroun](https://github.com/Aldroun) | Site Reliability Engineering |
| [@EliteSlacker](https://github.com/EliteSlacker) | Cyberdefense |
| [@kzahiras21](https://github.com/kzahiras21) | AI/ML ops |
| [@mapleury](https://github.com/mapleury) | Frontend UI |

## Ops — environment work with no repo diff

| # | Item | Source | Owner | Status |
| --- | --- | --- | --- | --- |
| 1 | Set `GOTRUE_EXTERNAL_WEB3_SOLANA_ENABLED=true` on the Dokploy Supabase `auth` service and redeploy — the SIWS button ships in web (PR #237) but self-hosted GoTrue ignores the legacy `SOLANA_ENABLED` name | `infra/supabase/vendor/CONFIG.md`, `vendor/docker-compose.yml` | @mzf11125 (feature) / @Aldroun (deploy) | open |
| 2 | Neo4j Browser remote access — keep tunnel-only path or expose bolt through a TLS-terminating Traefik TCP router (public bolt behind neo4j auth; contra the "bolt stays private" posture) | `deploy/README.md` ("Neo4j Browser access"), ADR-level | @EliteSlacker (decision) / @Aldroun (exec) | decision pending |
| 3 | Rehearsed restore — procedure exists, drill pending | `docs/restore-runbook.md`, reliability §4 | @Aldroun | open |
| 4 | Migration rollback rehearsal for V5–V7 on staging (T2 requirement) | `docs/reliability.md` §5, `AGENTS.md` | @Aldroun | open |
| 5 | Burn-rate alerts once metrics reach the collector; OTEL exporter env was dropped until a collector exists | `docs/reliability.md` §4–5 | @Aldroun | blocked on #8 |

## Reliability backlog (docs/reliability.md §5)

| # | Item | Owner | Status |
| --- | --- | --- | --- |
| 6 | Restore CI — GitHub billing blocker; nothing merges green today. Blocks verification of everything below | @daemon-blockint-tech (billing/admin) | blocked |
| 7 | Circuit breaker on the decision-model call (`JdkHttpTransport` 30 s) once it sits on a user-facing path | @Fatihmaull | open |
| 8 | OTEL collector — prerequisite for item 5 | @Aldroun | open |

## ADR acceptance criteria still open

| # | Item | Source | Owner | Status |
| --- | --- | --- | --- | --- |
| 9 | Self-hosted Supabase acceptance list: restore drill meeting RPO/RTO, upgrade/rollback rehearsal on staging, monitoring + incident runbooks, capacity/dependency-failure tests | `docs/adr/0002-self-hosted-supabase.md` | @Aldroun | open |
| 10 | Neo4j: dual-write ingestion path with atomic failure semantics; graph-ledger reconciliation on seeded data; backup/restore + upgrade runbooks; look-through perf test over 5-level hierarchy | `docs/adr/0004-neo4j-graph-store.md` | @Fatihmaull | open |

## Done / verified this cycle

- Web3 Solana sign-in (SIWS) — code and local GoTrue config verified; only the deployed-env flag remains (item 1).
- `ty` clean across `agents/` — 47 diagnostics resolved (question `Mapping`, `_record_run` run_id, `SubAgent` specs, `OctoApiClient` test fakes).
