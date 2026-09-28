-- V28__schema_rename_octo.sql
-- Rename the domain schema mesta -> octo (issue #223, follow-up to the #181 rebrand).
--
-- Grounded in:
--   issue #223   coordinated schema rename across every environment
--   ADR-0002     domain schemas stay isolated from Supabase internal schemas
--   CLAUDE.md    applied Flyway files are immutable — V1-V27 keep their mesta.* qualifiers
--
-- Why this moves objects instead of running ALTER SCHEMA ... RENAME:
--   * Deployed databases: ops runs `alter schema mesta rename to octo` against a verified
--     backup before this code boots, so mesta is already gone and this file is a no-op.
--   * Fresh databases (Testcontainers, new environments): Flyway creates octo for
--     flyway_schema_history, V1-V27 populate mesta.*, and this migration moves every
--     object into octo and drops the empty mesta schema — the same end state.
--   * RENAME cannot converge the fresh-database path: octo already exists there.
--
-- Grants, triggers and RLS policies are OID-bound to their objects in the catalog and
-- survive `set schema`. Two things do not travel cleanly:
--   * plpgsql function bodies store schema-qualified references as text that resolves at
--     execution time, so each function is moved with its OID (policies and triggers
--     depend on that OID) and then recreated from its own regenerated definition with
--     every mesta. qualifier rewritten to octo.
--   * V3's `grant usage on schema mesta` belongs to the schema object itself and is
--     re-issued below.

create schema if not exists octo;

do $$
declare
    obj record;
begin
    if not exists (select 1 from pg_namespace where nspname = 'mesta') then
        return;
    end if;

    for obj in select tablename as name from pg_tables where schemaname = 'mesta' loop
        execute format('alter table mesta.%I set schema octo', obj.name);
    end loop;
    for obj in select viewname as name from pg_views where schemaname = 'mesta' loop
        execute format('alter view mesta.%I set schema octo', obj.name);
    end loop;
    for obj in select matviewname as name from pg_matviews where schemaname = 'mesta' loop
        execute format('alter materialized view mesta.%I set schema octo', obj.name);
    end loop;
    for obj in select sequencename as name from pg_sequences where schemaname = 'mesta' loop
        execute format('alter sequence mesta.%I set schema octo', obj.name);
    end loop;
    for obj in select t.typname as name
                 from pg_type t join pg_namespace n on n.oid = t.typnamespace
                where n.nspname = 'mesta' loop
        execute format('alter type mesta.%I set schema octo', obj.name);
    end loop;
    for obj in select p.oid as funcoid, p.proname as name,
                      pg_get_function_identity_arguments(p.oid) as args
                 from pg_proc p join pg_namespace n on n.oid = p.pronamespace
                where n.nspname = 'mesta' loop
        execute format('alter function mesta.%I(%s) set schema octo', obj.name, obj.args);
        execute replace(pg_get_functiondef(obj.funcoid), 'mesta.', 'octo.');
    end loop;

    -- Plain drop, not cascade: anything left behind means an object type was missed
    -- above and must be handled deliberately rather than silently destroyed.
    drop schema mesta;
end $$;

comment on schema octo is
    'OCTO domain schema. Kept separate from Supabase internal schemas (ADR-0002).';

-- V3 granted usage on the mesta schema object, which no longer exists.
grant usage on schema octo to "${runtime_role}";
