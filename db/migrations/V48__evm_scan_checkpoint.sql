-- V48__evm_scan_checkpoint.sql
-- Per-chain high-water mark for the EVM poller (#494).
--
-- The scan used to resume at max(onchain_transfer.slot) + 1. A finished range that staged
-- nothing — no watched transfers, only skipped contracts, or only malformed logs — left no
-- row, so the next poll repeated the same range. Arbitrum quiet periods rescanned from the
-- last staged transfer every minute.
--
-- This table is operational cursor state, not a ledger fact. Positions stay derived from
-- instrument_flow. One row per chain; scanned_through is the last block the poller finished
-- scanning, inclusive. The runtime role may insert and advance it, not delete it: a backfill
-- is an operator reset of this row, the same limitation the scan already documents.
--
-- No tenant_id: one poll covers every tenant's watched addresses on the chain. The policy
-- admits only TenantScope.All (app.tenant_ids = '*'), matching JdbcOnchainStagingStore.

create table octo.evm_scan_checkpoint (
    chain            text        primary key,
    scanned_through  bigint      not null,
    updated_at       timestamptz not null default now(),

    constraint evm_scan_checkpoint_chain_shape
        check (char_length(chain) between 1 and 63 and chain ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    constraint evm_scan_checkpoint_block_nonnegative
        check (scanned_through >= 0)
);

comment on table octo.evm_scan_checkpoint is
    'Last EVM block the poller finished scanning, per chain. Quiet ranges advance this even when they stage no transfers.';
comment on column octo.evm_scan_checkpoint.scanned_through is
    'Inclusive block number. The next poll starts at scanned_through + 1. Never moves backward.';

grant select, insert, update on octo.evm_scan_checkpoint to "${runtime_role}";

alter table octo.evm_scan_checkpoint enable row level security;
create policy platform_scope on octo.evm_scan_checkpoint
    using (octo.rls_admits(null::uuid))
    with check (octo.rls_admits(null::uuid));
