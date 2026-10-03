-- V47__onchain_scan_checkpoint.sql
-- Stored resume cursor for the EVM log scan (#494).
--
-- The scan used to derive its cursor as max(slot) over staged transfers, so a block range
-- with no watched transfers was never recorded as scanned: after any quiet period every poll
-- re-walked the whole gap to the last staged transfer. This table records the highest block
-- the scan actually finished per chain, so each poll resumes after the last completed window.
--
-- One row per chain, platform-level (no tenant): the scan itself is a platform pass over
-- every watched address, same footing as octo.instrument. The cursor only moves forward —
-- a reset for backfill is an operator's manual `update`, never the scan's own write.
--
-- Grounded in:
--   V10__onchain_ingestion.sql        grant/comment conventions for runtime-written tables
--   V30__pre_v9_entity_tenant_scoping.sql  instrument stays unscoped (global reference data)

create table octo.onchain_scan_checkpoint (
    chain         text        primary key,
    through_block bigint      not null,
    updated_at    timestamptz not null default now(),

    constraint onchain_scan_checkpoint_through_block check (through_block >= 0)
);

comment on table octo.onchain_scan_checkpoint is
    'Per-chain resume cursor for the EVM log scan: the highest block whose Transfer-log window fully completed. Advanced only after a window''s legs are staged; a crash re-scans and dedupes.';
comment on column octo.onchain_scan_checkpoint.through_block is
    'Highest finalized block fully scanned on this chain. Never decreases — moving it backward is a manual backfill operation.';
comment on column octo.onchain_scan_checkpoint.updated_at is
    'When the checkpoint last advanced; a stale row per chain means the poller is down or stuck.';

grant select, insert, update on octo.onchain_scan_checkpoint to "${runtime_role}";
