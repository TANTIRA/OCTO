<!-- TANTIRA repo template. Source of truth: repo TANTIRA. Fill every [ ] placeholder. Do not edit the "Company rules" sections — they are identical in every repo. -->

# AGENTS.md — Mesta-Asset

Private-equity investment platform: a standardized Ontology (prospects, funds, portfolio companies, LPs, GPs) on a single IBOR, consolidating CRMs, financial and market-data providers, documents, and third-party/open-source feeds — with configurable deal screening, AI-assisted due diligence and IC reporting, performance analytics, low-code models, alerts, NL queries, LP reports, and governed workflows. One database, one system, one process.

- **Product line:** Operations
- **Owners (CODEOWNERS team):** @TANTIRA/mesta-asset
- **Default risk tier:** T1 — anything writing financial data (IBOR, reconciliation, migrations, auth, vendor ingestion) is T2. See Risk tiers below.
- **Runtime:** Kotlin on Java 21, Spring Boot 3, Gradle (Kotlin DSL), self-hosted Supabase PostgreSQL + Flyway

## Commands

```bash
./gradlew build                                     # install dependencies and compile
./gradlew bootRun                                   # run locally
./gradlew test                                      # run the full test suite
./gradlew test --tests 'com.mesta.asset.FooTest'    # run a single test
./gradlew check                                     # lint and format check
./gradlew bootJar                                   # production build
./gradlew bootRun                                   # migrations run at boot via Spring Flyway
flyway migrate                                      # ad-hoc migration (flyway CLI; the Gradle plugin is gone — it was broken on Gradle 9)
```

Run `./gradlew check` and `./gradlew test` before every pull request. If a command here is wrong, fix this file in the same PR.

## Layout and boundaries

| Path | What lives there | Change rules |
| --- | --- | --- |
| `modules/ibor-core/` | Transaction ledger, position and cash derivation, corporate actions | T2 — writes financial data |
| `modules/lookthrough/` | Entity and instrument hierarchies, exposure aggregation | Normal PR; hierarchy model changes are T2 |
| `modules/ingestion/` | Vendor adapters, feed normalization, mapping | T2 — touches external financial data |
| `modules/recon/` | Reconciliation and discrepancy detection | T2 |
| `modules/analytics/` | Metrics engine (IRR, TVPI, MOIC, DPI), low-code model authoring, artifact generation | Normal PR; metric definitions touching financial data are T2 |
| `modules/deal-sourcing/` | Prospect pipeline, configurable screening, DDQ assistance, IC report generation | T2 — AI-supported investment decisions; see AI features below |
| `modules/workflow/` | Approvals, task routing, operational processes | Normal PR |
| `modules/control-panel/` | AI alerting rules, NL query, email drafting, news matching | T2 — agents that act; see AI features below |
| `modules/api/` | REST API, auth boundary | Auth endpoints T2 |
| `modules/ontology/` | OWL/SHACL validator and TypeQL schema parser — the CI gate for `ontology/` | T2 — loosening the gate weakens ontology review |
| `modules/*/src/test/` | Tests | Normal PR |
| `infra/supabase/`, `infra/`, `.github/workflows/`, `Dockerfile` | Pinned self-hosted Supabase deployment, pipelines, and infrastructure | Needs Platform, DevOps, and Security review; tier T2 |
| `db/migrations/` | Flyway migrations | Never edit a migration that already ran; add a new one |
| `ontology/` | OWL/SHACL shapes and TypeQL 3.0 investment schema (`mesta-investment.tql`) | T2 — CTO owns; SemVer; deprecate before deleting |
| `.env*`, keys, certificates | Secrets | Never commit. `.env.example` holds names only, no values |

Do not create new top-level directories without an ADR.

## Company rules — workflow

Every change follows the SDLC of PT Antero Daemon Technology. Short version:

1. Work starts from a GitHub Issue. No issue, no PR.
2. Branch from `main`: `feat/<issue>-<slug>`, `fix/…`, `chore/…`, `hotfix/…`. Branch lives at most 2 days.
3. Conventional Commits: `feat:`, `fix:`, `refactor:`, `docs:`, `test:`, `chore:`; `!` marks a breaking change.
4. Keep the PR under 400 changed lines, excluding generated files and lockfiles. Split larger work and hide it behind a feature flag.
5. PR links its issue (`Closes #123`), states the risk tier, how it was tested, impact (API, DB migration, ontology, config, infra), and a rollback plan for T2 and above.
6. Merge by squash, only after CI is green and the required reviews are in. Never push straight to `main`.
7. Open the PR description with SCQA: Situation, Complication, Question, Answer. Answer first, evidence after.

## Company rules — risk tiers

| Tier | Typical change | What it requires |
| --- | --- | --- |
| T0 | Docs, UI copy, tests, non-production config | 1 reviewer, CI green |
| T1 | Ordinary feature or bugfix, no sensitive data | 1 CODEOWNER, QA check on staging |
| T2 | Auth/IAM/SSO, personal or financial data, destructive DB migration, infra, breaking API or library change, ontology schema, prompts or models used for decisions | 2 reviewers including a Tech Lead, security review, threat model, tested rollback, 24h on staging |
| T3 | Smart contract deploy or upgrade on mainnet, key management, Tantira-Defense, release into a client on-prem or air-gapped environment | 2 domain reviewers, red team review, rehearsal, CTO approval |

When unsure between two tiers, take the higher one. Raise the tier if review uncovers new impact.

## Company rules — testing

- Unit tests for business logic. Integration tests for API and database paths, using containers.
- Contract tests between services. End-to-end only for critical flows: login/SSO, transactions, main operational flow.
- New code in core modules: at least 70% coverage. Every bug fix ships with a test that reproduces the bug.
- Test data is synthetic or masked. Raw production data is never used locally, in dev, or on staging.

## Company rules — security and data

- Secrets live in the secret manager. Never in the repo, an image, a log, a prompt, or chat. A secret that was ever committed counts as leaked and must be rotated.
- Data classes: Public, Internal, Confidential, Strictly Confidential. Client data, personal data, financial data, and credentials are at least Confidential.
- Confidential and Strictly Confidential data never goes to a public LLM API. Use the approved self-hosted model instead.
- Personal data features record purpose, legal basis, retention, and access. High-risk processing needs a DPIA before code is written (UU PDP No. 27/2022).
- Validate input at trust boundaries. Never log personal data or tokens.
- Report any suspected leak to the Security Blue Team the same day.

## Company rules — for AI coding agents

You may use Claude Code, Codex, Devin, Windsurf, OpenCode, or Oh My Pi on this repo. These rules always apply:

- The PR author is fully responsible for merged code, whoever or whatever wrote it.
- Every agent change goes through a normal PR and human review. Agents never merge, never deploy, never push to `main`.
- Never put secrets, private keys, personal data, client data, or defense data into a prompt or into a tool that has not been approved.
- Agents run with least privilege: no production credentials, no deployer wallet or key.
- Third-party MCP servers, skills, and plugins need Security Blue Team review first. The approved list lives in repo TANTIRA.
- Label the PR `ai-assisted` when an agent wrote most of the change. In T3 repos a second reviewer reads that code line by line.
- Do not add a dependency unless the PR explains why. Copyleft licences (GPL, AGPL) need CTO approval.
- Do not invent APIs, flags, or config keys. Read the code or the documentation first; if it is still unclear, ask in the issue instead of guessing.
- Parallel sessions build the same slice. Before claiming work, comment "in progress" on the issue and check recent PRs for a competing implementation — #43/#53, #83/#84, and the Ledger.kt merge all broke `main` this way.
- Verify `main` compiles before any merge. When CI is unavailable, a local `./gradlew check test` before merge is the only gate; a PR that was green on its branch can still break `main` if the base moved.
- Never run destructive commands: `rm -rf` outside a build directory, `git push --force` on a shared branch, dropping or truncating a database, rewriting published history.
- Leave a `TODO(issue-id)` instead of silently skipping a requirement.

<!-- Keep the sections below for repos that touch ontology, contracts, or AI. Delete the ones that do not apply. -->

## Special paths in this repo

**Ontology (`ontology/`)** — owner: CTO

- Ontology is code: Turtle files (OWL plus SHACL shapes) and TypeQL schema live in Git.
- CI must pass RDF syntax checks and SHACL validation against the sample data.
- Version with `owl:versionIRI` using SemVer. Removing or redefining a class or property is a MAJOR change and tier T2.
- Mark with `owl:deprecated` for at least one release before deleting.
- List the impact on consumers (API, BI dashboards, agents) in the PR.
- Design and review changes against `docs/ontology-design-guidelines.md` — domain-driven, DRY, open/closed, composition over hierarchies.

**AI features (`modules/control-panel/` — alerting rules, NL queries, email drafts)** — owner: AI Tech Lead

- Prompts, agent configuration, and model versions are versioned in Git, not only in a dashboard.
- Every AI feature has an eval set covering normal, edge, and prompt-injection cases, with a pass threshold enforced in CI.
- Agents that can act (write data, send messages, transact) use a tool allowlist, least privilege, cost limits, and human approval for high-impact actions.
- Record the source and licence of every model and dataset.

## Definition of Done

- [ ] Acceptance criteria met and verified
- [ ] Review for the tier completed, CI green
- [ ] Tests added or updated
- [ ] No open High or Critical findings from SAST, secret scanning, or dependency scanning
- [ ] Docs updated: README, API, ADR, runbook, CHANGELOG
- [ ] Logs, metrics, and alerts exist for new behaviour (T1 and above)
- [ ] Ran on staging, rollback plan ready (T2 and above)

## References

Company documents live in repo TANTIRA: `SDLC-Tim-TANTIRA.md` (process, gates, security), `Methodology.md` (how we work and decide), `Design-System.md` (UI tokens and components).
