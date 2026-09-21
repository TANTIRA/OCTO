<!-- TANTIRA repo template. Claude Code reads CLAUDE.md, not AGENTS.md, so this file imports it. Keep this file short; put shared rules in AGENTS.md. -->

@AGENTS.md

# Claude Code — Mesta-Asset

## Before you start

- Read the issue and state the risk tier in your plan. T2 and above: write the plan first and get it agreed in the issue before editing code.
- Use plan mode for changes under `modules/ibor-core/`, `modules/recon/`, `modules/ingestion/`, `ontology/`, `db/migrations/`, `infra/`, and any auth path.

## While working

- Run `./gradlew check` and `./gradlew test` before opening a PR. Do not open a PR with a red test.
- Keep the diff under 400 lines. If it grows, stop and split the work.
- Prefer editing existing files over adding new ones. New top-level directories need an ADR.
- Write the PR description as SCQA, with the risk tier, test evidence, impact, and rollback plan.

## Never

- Never commit or push to `main`, never merge, never deploy.
- Never run `git push --force` on a shared branch, `rm -rf` outside a build directory, or any command that drops or truncates a database.
- Never put secrets, personal data, client data, or defense data into a prompt or an unapproved tool.
- Never use production credentials, deployer keys, or wallets.

## Repo facts Claude keeps forgetting

<!-- Add corrections here the second time Claude repeats a mistake. Keep each line concrete. -->

- Use Gradle (`./gradlew`), not Maven.
- IBOR positions and cash are derived from the transaction ledger. Never write position state directly.
- Integration tests use Testcontainers; Docker must be running.
- Flyway migrations live in `db/migrations/` — never edit one that already ran.
