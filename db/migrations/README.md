# db/migrations

Flyway migrations for the Mesta-Asset PostgreSQL schema (self-hosted Supabase `db` service).

Rules per `AGENTS.md`:

- Never edit a migration that already ran — add a new one.
- Naming: `V<version>__<description>.sql` (e.g. `V1__init.sql`).
- Migrations run as the dedicated migration identity (`DB_MIGRATION_*`), not the app runtime user.
- T2 by default — schema changes ship with the PR that needs them.
