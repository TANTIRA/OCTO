-- Post-restore verification: the executed form of docs/restore-runbook.md §7.
--
-- Runs against a RESTORED database with no application attached — which is the whole point:
-- Flyway's own validation, the audit chain, and the append-only triggers can all be checked
-- before the restored instance is allowed to take traffic.
--
-- Variables (psql -v), all required:
--   exp_ledger      row count of octo.ledger_event immediately before the loss window
--   exp_audit       row count of octo.audit_event immediately before the loss window
--   exp_flyway      installed_rank high-water mark immediately before the loss window
--   exp_flyway_md5  md5 of the ordered installed_rank|version|script set immediately
--                   before the loss window (the whole history, not just its head)
--   runtime_role    the application's runtime role name (deploy default: octo)
--
-- Emits one row of results. `verdict` is PASS only when every check passes.

\if :{?exp_ledger}\else \quit \endif
\if :{?exp_audit}\else \quit \endif
\if :{?exp_flyway}\else \quit \endif
\if :{?exp_flyway_md5}\else \quit \endif
\if :{?runtime_role}\else \quit \endif

-- Append-only enforcement is *attempted*, not just detected: a trigger that exists in the
-- catalog but is disabled (tgenabled 'D'), or whose function no longer raises, passes a
-- catalog check while mutations go through. Each probe runs inside an exception block, so
-- a rejected update rolls the whole subtransaction back and nothing is left behind; a
-- probe that "succeeds" is a same-value update, which changes no data either way.
create temporary table if not exists _post_restore_probe (
    thing text primary key,
    enforced boolean,
    detail text
);
truncate _post_restore_probe;

do $$
declare n int;
begin
    begin
        update octo.ledger_event set external_id = external_id
         where ctid = (select ctid from octo.ledger_event limit 1);
        get diagnostics n = row_count;
        insert into _post_restore_probe (thing, enforced, detail) values
            ('ledger_event', case when n > 0 then false else null end,
             case when n > 0 then 'update committed unchallenged'
                  else 'no rows to probe' end);
    exception when others then
        insert into _post_restore_probe (thing, enforced, detail)
        values ('ledger_event', true, sqlerrm);
    end;
    begin
        update octo.audit_event set action = action
         where ctid = (select ctid from octo.audit_event limit 1);
        get diagnostics n = row_count;
        insert into _post_restore_probe (thing, enforced, detail) values
            ('audit_event', case when n > 0 then false else null end,
             case when n > 0 then 'update committed unchallenged'
                  else 'no rows to probe' end);
    exception when others then
        insert into _post_restore_probe (thing, enforced, detail)
        values ('audit_event', true, sqlerrm);
    end;
end $$;

with
flyway_state as (
    select count(*) as rows_total,
           count(*) filter (where not success) as rows_failed,
           coalesce(max(installed_rank), 0) as head_rank,
           md5(string_agg(installed_rank::text || '|' || version || '|' || script,
                          E'\n' order by installed_rank)) as history_md5
    from octo.flyway_schema_history
),
counts as (
    select (select count(*) from octo.ledger_event) as ledger_rows,
           (select count(*) from octo.audit_event) as audit_rows,
           (select coalesce(max(seq), 0) from octo.audit_event) as audit_head_seq
),
-- Append-only controls must have survived the restore *enabled*: the triggers travel
-- with the table, but a catalog row with tgenabled 'D' (disabled) or 'R' (replica-only)
-- does not fire. 'O' is enabled, 'A' is enabled-always.
append_only as (
    select
        (select count(*) from pg_trigger t
          join pg_class c on c.oid = t.tgrelid
          join pg_namespace n on n.oid = c.relnamespace
         where n.nspname = 'octo' and c.relname = 'ledger_event'
           and t.tgname = 'ledger_event_append_only' and not t.tgisinternal
           and t.tgenabled in ('O','A')) = 1 as ledger_trigger,
        (select count(*) from pg_trigger t
          join pg_class c on c.oid = t.tgrelid
          join pg_namespace n on n.oid = c.relnamespace
         where n.nspname = 'octo' and c.relname = 'audit_event'
           and t.tgname = 'audit_event_append_only' and not t.tgisinternal
           and t.tgenabled in ('O','A')) = 1 as audit_trigger,
        exists (
            select 1 from pg_proc p join pg_namespace n on n.oid = p.pronamespace
             where n.nspname = 'octo' and p.proname = 'ledger_event_reject_mutation') as reject_fn
),
-- The attempted mutations, recorded by the DO block above. enforced is true only when
-- the update actually raised; null means there was no row to probe (unverifiable, and
-- unverifiable is not PASS).
mutation_blocked as (
    select
        max(case when thing = 'ledger_event' then enforced end) as ledger_blocked,
        max(case when thing = 'audit_event' then enforced end) as audit_blocked,
        max(case when thing = 'ledger_event' then detail end) as ledger_detail,
        max(case when thing = 'audit_event' then detail end) as audit_detail
    from _post_restore_probe
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
        when f.history_md5 is distinct from :'exp_flyway_md5' then 'FAIL'
        when cc.ledger_rows <> :exp_ledger then 'FAIL'
        when cc.audit_rows <> :exp_audit then 'FAIL'
        when not a.ledger_trigger or not a.audit_trigger then 'FAIL'
        when not a.reject_fn then 'FAIL'
        when m.ledger_blocked is not true or m.audit_blocked is not true then 'FAIL'
        when not r.runtime_role_present or r.runtime_is_super then 'FAIL'
        else 'PASS'
    end as verdict,
    f.rows_failed                      as flyway_failed_rows,
    f.head_rank                        as flyway_head_rank,
    :exp_flyway                        as flyway_expected_rank,
    f.history_md5                      as flyway_history_md5,
    :'exp_flyway_md5'                  as flyway_expected_history_md5,
    cc.ledger_rows                     as ledger_rows,
    :exp_ledger                        as ledger_expected,
    cc.audit_rows                      as audit_rows,
    :exp_audit                         as audit_expected,
    cc.audit_head_seq                  as audit_head_seq,
    a.ledger_trigger                   as ledger_append_only_enabled,
    a.audit_trigger                    as audit_append_only_enabled,
    a.reject_fn                        as append_only_function_present,
    m.ledger_blocked                   as ledger_update_blocked,
    m.audit_blocked                    as audit_update_blocked,
    m.ledger_detail                    as ledger_probe_detail,
    m.audit_detail                     as audit_probe_detail,
    r.runtime_role_present             as runtime_role_present,
    not r.runtime_is_super             as runtime_role_not_superuser
from flyway_state f, counts cc, append_only a, mutation_blocked m, roles r;
