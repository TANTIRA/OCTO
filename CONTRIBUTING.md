# Contributing

The rules for this repo live in [AGENTS.md](AGENTS.md) and apply to people and AI agents alike:

- Workflow (issue first, branch naming, Conventional Commits, PR size, SCQA description): see *Company rules — workflow*.
- Risk tiers and the reviews each tier needs: see *Company rules — risk tiers*.
- Paths with extra rules (`ontology/`, `db/migrations/`, `infra/`, AI features): see *Layout and boundaries* and *Special paths in this repo*.

Before opening a pull request, run `./gradlew check` with Docker running so the Testcontainers integration tests execute instead of being skipped.
