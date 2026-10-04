-- V48__asset_unique_supersession.sql
-- One correction per asset row: a supersedes_id may appear at most once (#566).
--
-- V44 made supersedes_id unique for ledger_event, valuation_event, onchain_transfer and
-- instrument_flow. octo.asset was left with the plain asset_supersedes_id_idx from V38, so two
-- corrections of the same asset were accepted and the lineage forked into two current rows.
-- Graph reconciliation reports that fork rather than guessing a head; the table should refuse
-- it at insert, the same way V44 does for the other append-only facts. A later row cannot
-- repair the fork: asset is append-only.
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
            select 'asset' t, string_agg(supersedes_id::text, ', ') ids
              from (select supersedes_id from octo.asset where supersedes_id is not null
                     group by supersedes_id having count(*) > 1) f
           ) per_table
     where ids is not null;
    if forks is not null then
        raise exception E'rows superseded more than once; resolve before V47:\n%', forks
            using errcode = 'unique_violation';
    end if;
end;
$$;

create unique index asset_supersedes_unique
    on octo.asset (supersedes_id) where supersedes_id is not null;

-- The unique index covers every lookup the plain one served (V38).
drop index octo.asset_supersedes_id_idx;
