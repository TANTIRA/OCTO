-- V3__runtime_role_grants.sql
-- Least-privilege access to the mesta schema for the application runtime role.
--
-- Grounded in:
--   infra/docker-compose.yml     Flyway runs as DB_MIGRATION_USER; the api connects as DB_USER
--   .env.example                 DB_USER is the runtime role — non-superuser, app schemas only
--   data-security-governance.md  "The IBOR ledger is append-only"; access proportional to role
--
-- V1 and V2 create every object as the migration role and grant nothing, so the runtime role could
-- not read or write mesta at all (issue #17). ${runtime_role} is a Flyway placeholder set from
-- DB_USER, because the role name differs per environment. It is quoted: login names are exact.
--
-- The tables are append-only, so the runtime role gets select and insert only; update and delete
-- fail on privilege before the append-only triggers are reached. Tables are named rather than
-- granted "on all tables" because flyway_schema_history lives in mesta and must stay out of reach.
-- A later migration that creates a table grants the runtime role what that table needs.

grant usage on schema mesta to "${runtime_role}";

grant select, insert
    on mesta.ledger_event,
       mesta.document_classification,
       mesta.claim_assessment
    to "${runtime_role}";
