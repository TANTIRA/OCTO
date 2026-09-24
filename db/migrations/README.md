# db/migrations

Flyway migrations for the Mesta-Asset PostgreSQL schema (self-hosted Supabase `db` service).

Rules per `AGENTS.md`:

- Never edit a migration that already ran — add a new one.
- Naming: `V<version>__<description>.sql` (e.g. `V1__init.sql`).
- Migrations run as the dedicated migration identity (`DB_MIGRATION_*`), not the app runtime user.
- The runtime role (`DB_USER`) must exist before migrations run; V3 grants it access through the `runtime_role` placeholder and fails if the role is missing.
- A migration that creates a table grants the runtime role exactly what that table needs (`select, insert` for append-only tables). Never grant on `flyway_schema_history`, which lives in `mesta`.
- T2 by default — schema changes ship with the PR that needs them.
