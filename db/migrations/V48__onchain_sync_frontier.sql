-- V48__onchain_sync_frontier.sql
-- Resumable history-descent cursor for the Solana sync (#509).
--
-- The sync walks getTransactionsForAddress newest-first, bounded to a page budget per pass.
-- A truncated pass used to abandon the walk: the derived cursor (max staged slot) had already
-- moved to the top of the gap, so everything older than the newest page was never fetched.
-- One row per watched address records an in-progress descent: floor_slot is the exclusive
-- lower bound the walk is filling (null reaches genesis — the cold-wallet backfill), and
-- ceiling_slot is the lowest slot already fetched; the next pass reads slot.lte = ceiling_slot
-- until the walk empties and the row is cleared.
--
-- One row per (chain, address), platform-level like V47's onchain_scan_checkpoint: the sync
-- is a platform pass over every watched address, and the row is pipeline state, not a fact.
--
-- Grounded in:
--   V47__onchain_scan_checkpoint.sql   grant/comment conventions for a runtime-written cursor

create table octo.onchain_sync_frontier (
    chain         text        not null,
    address       text        not null,
    floor_slot    bigint,
    ceiling_slot  bigint      not null,
    updated_at    timestamptz not null default now(),

    primary key (chain, address),
    constraint onchain_sync_frontier_floor check (floor_slot is null or floor_slot >= 0),
    constraint onchain_sync_frontier_ceiling check (ceiling_slot >= 0),
    constraint onchain_sync_frontier_order check (floor_slot is null or floor_slot < ceiling_slot)
);

comment on table octo.onchain_sync_frontier is
    'In-progress history descent per watched address (#509). Written when a sync pass exhausts its page budget mid-gap; cleared when the descent reaches its floor.';
comment on column octo.onchain_sync_frontier.floor_slot is
    'Exclusive lower bound of the gap being filled — the staged cursor when the walk began. Null: the descent runs to genesis (backfill of a wallet the poller never synced).';
comment on column octo.onchain_sync_frontier.ceiling_slot is
    'Lowest slot fetched so far, inclusive — the next pass filters slot.lte = ceiling_slot and dedup absorbs the boundary-slot refetch.';

grant select, insert, update, delete on octo.onchain_sync_frontier to "${runtime_role}";
