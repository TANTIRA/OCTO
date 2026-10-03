-- V49__asset_supersession_unique.sql
-- V44 made supersedes_id unique for ledger_event, valuation_event, onchain_transfer and
-- instrument_flow — one correction per row — but octo.asset was left out (#566). Two
-- corrections of the same asset row are accepted today and fork its lineage into two
-- current rows; asset is append-only (V11 triggers), so the fork can never be repaired.
-- The reconciler already reports such a fork instead of guessing a head, but the ledger
-- should refuse it the way V44's tables do: a unique partial index at insert.
--
-- Same pre-check as V44: a deployed database already holding a fork stops here with the
-- offending ids instead of a bare unique-violation, so ops can resolve it (pick the
-- surviving correction, re-issue the other as a correction of it) before re-running.

do $$
declare
    forks text;
begin
    select string_agg(supersedes_id::text, ', ')
      into forks
      from (select supersedes_id from octo.asset where supersedes_id is not null
             group by supersedes_id having count(*) > 1) f;
    if forks is not null then
        raise exception E'asset rows superseded more than once; resolve before V49:\n%', forks
            using errcode = 'unique_violation';
    end if;
end;
$$;

create unique index asset_supersedes_unique
    on octo.asset (supersedes_id) where supersedes_id is not null;

-- The unique index covers every lookup the plain one served (V38).
drop index octo.asset_supersedes_id_idx;
