-- V44__unique_supersession.sql
-- One correction per row: a supersedes_id may appear at most once per table.
--
-- Derivation resolves corrections with resolveCurrent (ibor-core Ledger.kt), which refuses a
-- target superseded twice ("... is superseded more than once"). The supersedes_id indexes were
-- not unique, so two corrections of the same row (two operators, a manual fix, a staging fork
-- the promoter copies) were accepted. These tables are append-only, so a later correction
-- cannot repair the fork either: every derivation over that ledger, valuation or wallet throws
-- from then on. A unique partial index makes the database refuse the second correction at insert.
--
-- If a deployed database already holds a fork, the pre-check stops the migration with the
-- offending ids instead of a bare unique-violation, so ops can resolve it (pick the surviving
-- correction, re-issue the other as a correction of it) before re-running.

do $$
declare
    forks text;
begin
    select string_agg(format('%s: %s', t, ids), E'\n')
      into forks
      from (
            select 'ledger_event' t, string_agg(supersedes_id::text, ', ') ids
              from (select supersedes_id from octo.ledger_event where supersedes_id is not null
                     group by supersedes_id having count(*) > 1) f
            union all
            select 'valuation_event', string_agg(supersedes_id::text, ', ')
              from (select supersedes_id from octo.valuation_event where supersedes_id is not null
                     group by supersedes_id having count(*) > 1) f
            union all
            select 'onchain_transfer', string_agg(supersedes_id::text, ', ')
              from (select supersedes_id from octo.onchain_transfer where supersedes_id is not null
                     group by supersedes_id having count(*) > 1) f
            union all
            select 'instrument_flow', string_agg(supersedes_id::text, ', ')
              from (select supersedes_id from octo.instrument_flow where supersedes_id is not null
                     group by supersedes_id having count(*) > 1) f
           ) per_table
     where ids is not null;
    if forks is not null then
        raise exception E'rows superseded more than once; resolve before V44:\n%', forks
            using errcode = 'unique_violation';
    end if;
end;
$$;

create unique index ledger_event_supersedes_unique
    on octo.ledger_event (supersedes_id) where supersedes_id is not null;
create unique index valuation_event_supersedes_unique
    on octo.valuation_event (supersedes_id) where supersedes_id is not null;
create unique index onchain_transfer_supersedes_unique
    on octo.onchain_transfer (supersedes_id) where supersedes_id is not null;
create unique index instrument_flow_supersedes_unique
    on octo.instrument_flow (supersedes_id) where supersedes_id is not null;

-- The unique indexes cover every lookup the plain ones served (V1, V10).
drop index octo.ledger_event_supersedes_idx;
drop index octo.onchain_transfer_supersedes_idx;
drop index octo.instrument_flow_supersedes_idx;
drop index octo.valuation_event_supersedes_idx;
