-- V17__arbitrum_evm_support.sql
-- Arbitrum/EVM support for the V10 onchain staging family: per-chain address
-- rules, the 'erc20' instrument kind, native ETH and canonical stablecoin seeds,
-- and a vendor-neutral name for the staging payload column.
--
-- Grounded in:
--   docs/arbitrum-ingestion-design.md   per-chain CHECK form, 'finalized' tag posture
--   docs/arbitrum-megaplan.md           phase ARB-1
--   V10__onchain_ingestion.sql          constraints widened here, never edited in place
--   V16__onchain_claim_evidence.sql     subject_address gets the same per-chain rule
--
-- Address shape is now chain-scoped: 'solana' rows keep the base58 rule, chains
-- named arbitrum-* take lowercase 0x hex, and any other chain satisfies neither
-- branch — an unknown chain fails closed instead of silently relaxing the rule.
-- The 'finalized' commitment CHECK is unchanged: the EVM adapter only ever
-- stages finalized-tagged blocks, so the invariant holds verbatim.

-- tracked_address.address — renamed: the constraint is no longer Solana-only.
alter table mesta.tracked_address
    drop constraint tracked_address_solana_shape;
alter table mesta.tracked_address
    add constraint tracked_address_address_shape
        check (
            (chain = 'solana' and address ~ '^[1-9A-HJ-NP-Za-km-z]{32,44}$')
            or (chain ~ '^arbitrum-' and address ~ '^0x[0-9a-f]{40}$')
        );

-- onchain_transfer.wallet / .mint_address — mint carries the ERC-20 contract.
alter table mesta.onchain_transfer
    drop constraint onchain_transfer_wallet_shape;
alter table mesta.onchain_transfer
    add constraint onchain_transfer_wallet_shape
        check (
            (chain = 'solana' and wallet ~ '^[1-9A-HJ-NP-Za-km-z]{32,44}$')
            or (chain ~ '^arbitrum-' and wallet ~ '^0x[0-9a-f]{40}$')
        );
alter table mesta.onchain_transfer
    drop constraint onchain_transfer_mint_shape;
alter table mesta.onchain_transfer
    add constraint onchain_transfer_mint_shape
        check (
            mint_address is null
            or (chain = 'solana' and mint_address ~ '^[1-9A-HJ-NP-Za-km-z]{32,44}$')
            or (chain ~ '^arbitrum-' and mint_address ~ '^0x[0-9a-f]{40}$')
        );

-- onchain_balance_snapshot.wallet; its mint_address was unchecked in V10 — the
-- same contract-address rule applies there, so it gets the shape check too.
alter table mesta.onchain_balance_snapshot
    drop constraint onchain_balance_snapshot_wallet_shape;
alter table mesta.onchain_balance_snapshot
    add constraint onchain_balance_snapshot_wallet_shape
        check (
            (chain = 'solana' and wallet ~ '^[1-9A-HJ-NP-Za-km-z]{32,44}$')
            or (chain ~ '^arbitrum-' and wallet ~ '^0x[0-9a-f]{40}$')
        );
alter table mesta.onchain_balance_snapshot
    add constraint onchain_balance_snapshot_mint_shape
        check (
            mint_address is null
            or (chain = 'solana' and mint_address ~ '^[1-9A-HJ-NP-Za-km-z]{32,44}$')
            or (chain ~ '^arbitrum-' and mint_address ~ '^0x[0-9a-f]{40}$')
        );

-- instrument.kind gains 'erc20'; instrument.mint_address goes per-chain.
alter table mesta.instrument
    drop constraint instrument_kind_known;
alter table mesta.instrument
    add constraint instrument_kind_known
        check (instrument_kind in (
            'native-token',
            'spl-token',
            'token-2022',
            'nft',
            'stake-account',
            'erc20',
            'other'
        ));
alter table mesta.instrument
    drop constraint instrument_mint_shape;
alter table mesta.instrument
    add constraint instrument_mint_shape
        check (
            mint_address is null
            or (chain = 'solana' and mint_address ~ '^[1-9A-HJ-NP-Za-km-z]{32,44}$')
            or (chain ~ '^arbitrum-' and mint_address ~ '^0x[0-9a-f]{40}$')
        );

-- instrument_flow.wallet
alter table mesta.instrument_flow
    drop constraint instrument_flow_wallet_shape;
alter table mesta.instrument_flow
    add constraint instrument_flow_wallet_shape
        check (
            (chain = 'solana' and wallet ~ '^[1-9A-HJ-NP-Za-km-z]{32,44}$')
            or (chain ~ '^arbitrum-' and wallet ~ '^0x[0-9a-f]{40}$')
        );

-- onchain_claim_evidence.subject_address (V16)
alter table mesta.onchain_claim_evidence
    drop constraint onchain_claim_evidence_address_shape;
alter table mesta.onchain_claim_evidence
    add constraint onchain_claim_evidence_address_shape
        check (
            (chain = 'solana' and subject_address ~ '^[1-9A-HJ-NP-Za-km-z]{32,44}$')
            or (chain ~ '^arbitrum-' and subject_address ~ '^0x[0-9a-f]{40}$')
        );

-- The staging payload column has never carried data (TODO #114 stands); renaming
-- it now keeps the name honest once a second adapter writes staging rows.
alter table mesta.onchain_transfer
    rename column helius_payload to vendor_payload;

comment on column mesta.onchain_transfer.vendor_payload is
    'The normalized provider response the row was derived from — lineage for re-derivation. Written by the adapter; currently null (see TODO #114).';
comment on table mesta.onchain_transfer is
    'Append-only staging for normalized onchain transfers (Solana and EVM chains). Vendor-neutral rows; the adapter''s raw response stays in vendor_payload for lineage.';
comment on column mesta.onchain_transfer.mint_address is
    'Null means the chain''s native asset (SOL, ETH); otherwise the token identifier — SPL/token-2022 mint or EVM contract address. Never resolved here — promotion joins mesta.instrument.';
comment on column mesta.onchain_transfer.commitment is
    'Always ''finalized'' — the only level/tag that cannot roll back (Solana rooted slots; EVM L1-finalized blocks). The adapter filters before insert.';
comment on column mesta.onchain_balance_snapshot.mint_address is
    'Null means the chain''s native balance row (SOL, ETH); otherwise the token identifier — mint or EVM contract.';
comment on table mesta.instrument is
    'Append-only registry of token-denominated instruments a wallet can hold. Mirrors ontology instrument/solana-mint/evm-contract; fiat currencies stay out (ledger_event.currency_code).';

-- Seeds: native ETH plus the two canonical USDC deployments on Arbitrum One.
-- USDC (native) and USDC.e (bridged) are different instruments keyed by
-- contract — never merged by symbol, never netted. Addresses are stored
-- lowercase, matching the 0x shape rule and the adapter's canonical form.
insert into mesta.instrument (
    external_key, chain, mint_address, instrument_kind, decimals, symbol,
    source_system, actor, ingestion_run_id, correlation_id
) values
    ('arbitrum-one:native', 'arbitrum-one', null, 'native-token', 18, 'ETH',
     'migration-v17', 'flyway', gen_random_uuid(), gen_random_uuid()),
    ('arbitrum-one:contract:0xaf88d065e77c8cc2239327c5edb3a432268e5831', 'arbitrum-one',
     '0xaf88d065e77c8cc2239327c5edb3a432268e5831', 'erc20', 6, 'USDC',
     'migration-v17', 'flyway', gen_random_uuid(), gen_random_uuid()),
    ('arbitrum-one:contract:0xff970a61a04b1ca14834a43f5de4533ebddb5cc8', 'arbitrum-one',
     '0xff970a61a04b1ca14834a43f5de4533ebddb5cc8', 'erc20', 6, 'USDC.e',
     'migration-v17', 'flyway', gen_random_uuid(), gen_random_uuid());
