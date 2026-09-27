-- infra/init-db-roles.sql — one-time bootstrap of the least-privilege roles the
-- api connects as (DB_USER) and Flyway migrates as (DB_MIGRATION_USER).
--
-- Run once per environment as a superuser, before the first api boot — Supabase
-- Studio SQL editor, or:
--   psql -h <db-host> -U postgres -f infra/init-db-roles.sql
-- The Studio editor has no psql variables, so passwords are placeholders:
-- generate strong values from the secret manager, substitute at run time, and
-- never commit the result.
--
-- Role names below are the deployment convention. If an environment uses other
-- names, keep them consistent here and in DB_USER / DB_MIGRATION_USER — the
-- V3+ migrations grant to "${runtime_role}", which Flyway resolves from DB_USER.

create role octo_app login password '<replace-at-run-time>';
create role octo_migrate login password '<replace-at-run-time>';

grant connect on database postgres to octo_app, octo_migrate;

-- V1 creates the mesta schema and V2+ create every object in it, so the
-- migration role needs database-level CREATE. It owns what it creates; V3+
-- grants the runtime role select/insert on exactly the tables it needs — never
-- "all tables", because flyway_schema_history lives in mesta too.
grant create on database postgres to octo_migrate;
