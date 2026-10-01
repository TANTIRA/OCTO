-- Post-restore verification: the executed form of docs/restore-runbook.md §7.
--
-- Runs against a RESTORED database with no application attached — which is the whole point:
-- Flyway's own validation, the audit chain, and the append-only triggers can all be checked
-- before the restored instance is allowed to take traffic.
--
-- Variables (psql -v), all required:
--   exp_ledger     row count of octo.ledger_event immediately before the loss window
--   exp_audit      row count of octo.audit_event immediately before the loss window
--   exp_flyway     installed_rank high-water mark immediately before the loss window
--   runtime_role   the application's runtime role name (deploy default: octo)
--
-- Emits one row of results. `verdict` is PASS only when every check passes.

\if :{?exp_ledger}\else \quit \endif
\if :{?exp_audit}\else \quit \endif
\if :{?exp_flyway}\else \quit \endif
\if :{?runtime_role}\else \quit \endif

with
flyway_state as (
    select count(*) as rows_total,
           count(*) filter (where not success) as rows_failed,
           coalesce(max(installed_rank), 0) as head_rank
    from octo.flyway_schema_history
),
counts as (
    select (select count(*) from octo.ledger_event) as ledger_rows,
           (select count(*) from octo.audit_event) as audit_rows,
           (select coalesce(max(seq), 0) from octo.audit_event) as audit_head_seq
),
-- Append-only controls must have survived the restore: the triggers travel with the
-- table, but a restore is exactly when you want to see it rather than assume it.
append_only as (
    select
        (select count(*) from pg_trigger t
          join pg_class c on c.oid = t.tgrelid
          join pg_namespace n on n.oid = c.relnamespace
         where n.nspname = 'octo' and c.relname = 'ledger_event'
           and t.tgname = 'ledger_event_append_only' and not t.tgisinternal) = 1 as ledger_trigger,
        (select count(*) from pg_trigger t
          join pg_class c on c.oid = t.tgrelid
          join pg_namespace n on n.oid = c.relnamespace
         where n.nspname = 'octo' and c.relname = 'audit_event'
           and t.tgname = 'audit_event_append_only' and not t.tgisinternal) = 1 as audit_trigger
),
-- The mutation itself, attempted and expected to raise. Never leaves a row behind.
mutation_blocked as (
    select
        (select count(*) from pg_trigger t
          join pg_class c on c.oid = t.tgrelid
          join pg_namespace n on n.oid = c.relnamespace
         where n.nspname = 'octo' and c.relname = 'ledger_event'
           and t.tgname = 'ledger_event_append_only' and not t.tgisinternal) = 1
        and exists (
            select 1 from pg_proc p join pg_namespace n on n.oid = p.pronamespace
             where n.nspname = 'octo' and p.proname = 'ledger_event_reject_mutation') as enforced
),
-- Migrations ran as a separate identity; the runtime role has no elevation.
roles as (
    select
        exists (select 1 from pg_roles where rolname = :'runtime_role' and not rolsuper) as runtime_role_present,
        coalesce((select rolsuper from pg_roles where rolname = :'runtime_role'), false) as runtime_is_super
)
select
    case
        when f.rows_failed > 0 then 'FAIL'
        when f.head_rank < :exp_flyway then 'FAIL'
        when cc.ledger_rows <> :exp_ledger then 'FAIL'
        when cc.audit_rows <> :exp_audit then 'FAIL'
        when not a.ledger_trigger or not a.audit_trigger then 'FAIL'
        when not m.enforced then 'FAIL'
        when not r.runtime_role_present or r.runtime_is_super then 'FAIL'
        else 'PASS'
    end as verdict,
    f.rows_failed                      as flyway_failed_rows,
    f.head_rank                        as flyway_head_rank,
    :exp_flyway                        as flyway_expected_rank,
    cc.ledger_rows                     as ledger_rows,
    :exp_ledger                        as ledger_expected,
    cc.audit_rows                      as audit_rows,
    :exp_audit                         as audit_expected,
    cc.audit_head_seq                  as audit_head_seq,
    a.ledger_trigger                   as ledger_append_only_trigger,
    a.audit_trigger                    as audit_append_only_trigger,
    m.enforced                         as append_only_function_present,
    r.runtime_role_present             as runtime_role_present,
    not r.runtime_is_super             as runtime_role_not_superuser
from flyway_state f, counts cc, append_only a, mutation_blocked m, roles r;
