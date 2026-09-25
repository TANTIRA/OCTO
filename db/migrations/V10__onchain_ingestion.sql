-- V10__onchain_ingestion.sql
-- Onchain (Solana-first) ingestion: instrument registry, watched-address set, append-only
-- staging for transfers and balance snapshots, and the instrument-keyed token ledger.
--
-- Grounded in:
--   ADR-0001                          PostgreSQL owns the ledger of record; vendor formats stay
--                                     inside modules/ingestion adapters
--   docs/helius-fit-assessment.md     read-only posture — the platform never signs; every row
--                                     records its Solana finality level
--   ontology/mesta-investment.tql     instrument / solana-mint / wallet / instrument-flow,
--                                     instrument-kind and instrument-flow-type @values,
--                                     solana-address @regex (base58, 32-44 chars)
--   V1__init.sql                      ledger_event is fiat-shaped (currency_code ^[A-Z]{3}$);
--                                     token flows cannot be represented there, so they get a
--                                     parallel append-only table rather than overloading it
--   V2__decision_staging.sql          staging boilerplate: source_system, actor,
--                                     ingestion_run_id, correlation_id, recorded_at,
--                                     supersedes_id + mandatory rationale
--   V8__tenant_access.sql             immutable header + append-only event replay pattern
--
-- Token positions are DERIVED from mesta.instrument_flow — never written. Amounts are raw
-- integer base units plus the decimals observed at ingestion; display conversion happens in
-- the reading layer. Helius USD values live only on the snapshot table: they are provider
-- valuations, never ledger facts. The chain column stays generic ("solana" in v1) so a second
-- chain does not force a schema rewrite.

-- -----------------------------------------------------------------------------
-- Instrument registry. external_key is the canonical identity that the ontology's
-- instrument-id mirrors ("solana:native", "solana:mint:<base58>"). A row is written once per
-- discovered instrument; metadata corrections are a later governed decision, so the table is
-- append-only without a supersedes path for now.
-- -----------------------------------------------------------------------------
create table mesta.instrument (
    id               uuid         primary key default gen_random_uuid(),
    external_key     text         not null,
    chain            text         not null,
    mint_address     text,
    instrument_kind  text         not null,
    decimals         smallint     not null,
    symbol           text,
    recorded_at      timestamptz  not null default now(),
    source_system    text         not null,
    actor            text         not null,
    ingestion_run_id uuid         not null,
    correlation_id   uuid         not null,

    constraint instrument_kind_known check (instrument_kind in (
        'native-token',
        'spl-token',
        'token-2022',
        'nft',
        'stake-account',
        'other'
    )),
    constraint instrument_decimals_range check (decimals between 0 and 255),
    -- mint_address is null for the chain's native asset, a base58 mint otherwise.
    constraint instrument_mint_shape
        check (mint_address is null or mint_address ~ '^[1-9A-HJ-NP-Za-km-z]{32,44}$'),
    constraint instrument_native_iff_no_mint
        check ((instrument_kind = 'native-token') = (mint_address is null))
);

comment on table mesta.instrument is
    'Append-only registry of token-denominated instruments a wallet can hold. Mirrors ontology instrument/solana-mint; fiat currencies stay out (ledger_event.currency_code).';
comment on column mesta.instrument.external_key is
    'Canonical identity shared with TypeDB instrument-id: "solana:native" or "solana:mint:<address>". Never a symbol — symbols are metadata.';
comment on column mesta.instrument.decimals is
    'SPL decimals are a u8 onchain (0..255). Raw-unit amounts in staging and instrument_flow are divided by 10^decimals only at display time.';

create unique index instrument_external_key_key on mesta.instrument (external_key);
create unique index instrument_chain_mint_key on mesta.instrument (chain, mint_address)
    nulls not distinct;

-- The native SOL instrument exists before any adapter runs so native transfers resolve to a
-- stable id. external_key is the join the promotion layer uses.
insert into mesta.instrument (
    external_key, chain, mint_address, instrument_kind, decimals, symbol,
    source_system, actor, ingestion_run_id, correlation_id
) values (
    'solana:native', 'solana', null, 'native-token', 9, 'SOL',
    'migration-v10', 'flyway', gen_random_uuid(), gen_random_uuid()
);

-- -----------------------------------------------------------------------------
-- Watched-address set. tracked_address is the immutable header keyed by (chain, address);
-- watch state itself is replayed from tracked_address_event exactly like tenant membership
-- (V8). The address -> tenant/deal attribution is Confidential platform data.
-- -----------------------------------------------------------------------------
create table mesta.tracked_address (
    chain           text         not null,
    address         text         not null,
    tenant_id       uuid         references mesta.tenant (id),
    label           text,
    recorded_at     timestamptz  not null default now(),
    source_system   text         not null,
    correlation_id  uuid         not null,

    primary key (chain, address),
    constraint tracked_address_solana_shape
        check (address ~ '^[1-9A-HJ-NP-Za-km-z]{32,44}$')
);

comment on table mesta.tracked_address is
    'Immutable header for one address the platform watches. Whether it is currently watched is derived from tracked_address_event, never stored here.';
comment on column mesta.tracked_address.tenant_id is
    'Which tenant''s data boundary this address sits in (V8). Null = platform-scoped watch.';

create table mesta.tracked_address_event (
    id              uuid         primary key default gen_random_uuid(),
    seq             bigint       generated always as identity,
    chain           text         not null,
    address         text         not null,
    event_type      text         not null,
    actor           text         not null,
    rationale       text,
    occurred_at     timestamptz  not null,
    correlation_id  uuid         not null,

    foreign key (chain, address) references mesta.tracked_address (chain, address),
    constraint tracked_address_event_type_known check (event_type in ('watched', 'unwatched')),
    constraint tracked_address_event_unwatch_rationale
        check (event_type <> 'unwatched' or length(btrim(coalesce(rationale, ''))) > 0)
);

comment on table mesta.tracked_address_event is
    'Append-only watch/unwatch history. The latest event by seq decides whether polling and webhooks accept the address.';

create index tracked_address_event_address_idx
    on mesta.tracked_address_event (chain, address, seq);

-- First event must be a watch; watch lands only while unwatched, unwatch only while watched.
-- Mirrors the V8 rules trigger so a bypassing writer cannot corrupt replay order.
create function mesta.tracked_address_event_rules() returns trigger
    language plpgsql
as $$
declare
    latest_type text;
    latest_at   timestamptz;
begin
    perform pg_advisory_xact_lock(
        hashtextextended('mesta.tracked_address:' || new.chain || ':' || new.address, 0));

    select e.event_type, e.occurred_at
      into latest_type, latest_at
      from mesta.tracked_address_event e
     where e.chain = new.chain and e.address = new.address
     order by e.seq desc
     limit 1;

    if new.event_type = 'watched' and latest_type = 'watched' then
        raise exception 'address % on % is already watched', new.address, new.chain
            using errcode = 'check_violation';
    end if;
    if new.event_type = 'unwatched' and latest_type is distinct from 'watched' then
        raise exception 'address % on % is not watched', new.address, new.chain
            using errcode = 'check_violation';
    end if;
    if new.occurred_at < latest_at then
        raise exception 'watch events of % on % must be in time order', new.address, new.chain
            using errcode = 'check_violation';
    end if;
    return new;
end;
$$;

create trigger tracked_address_event_rules
    before insert on mesta.tracked_address_event
    for each row
    execute function mesta.tracked_address_event_rules();

-- -----------------------------------------------------------------------------
-- Staging: normalized onchain transfers. One row per (chain, signature, account, instruction
-- path) — external_id carries that composite so poller replays and webhook/poller overlap
-- dedupe on the unique key. Rows are written only at commitment='finalized'; nothing
-- unfinalized is ever staged, so no reorg handling exists downstream.
-- -----------------------------------------------------------------------------
create table mesta.onchain_transfer (
    id               uuid            primary key default gen_random_uuid(),
    external_id      text            not null,
    chain            text            not null,
    signature        text            not null,
    slot             bigint          not null,
    block_hash       text,
    block_time       timestamptz     not null,
    commitment       text            not null,
    wallet           text            not null,
    counterparty     text,
    token_account    text,
    mint_address     text,
    amount_raw       numeric(38, 0)  not null,
    decimals         smallint        not null,
    direction        text            not null,
    transfer_kind    text            not null,
    helius_payload   jsonb,
    recorded_at      timestamptz     not null default now(),
    supersedes_id    uuid            references mesta.onchain_transfer (id),
    rationale        text,
    source_system    text            not null,
    actor            text            not null,
    ingestion_run_id uuid            not null,
    correlation_id   uuid            not null,

    constraint onchain_transfer_commitment_finalized check (commitment = 'finalized'),
    constraint onchain_transfer_direction_known check (direction in ('in', 'out', 'self', 'fee')),
    constraint onchain_transfer_kind_known check (transfer_kind in (
        'transfer-in',
        'transfer-out',
        'staking-reward',
        'airdrop',
        'unlock',
        'vesting-claim',
        'mint',
        'burn',
        'other'
    )),
    constraint onchain_transfer_decimals_range check (decimals between 0 and 255),
    constraint onchain_transfer_amount_nonnegative check (amount_raw >= 0),
    constraint onchain_transfer_wallet_shape
        check (wallet ~ '^[1-9A-HJ-NP-Za-km-z]{32,44}$'),
    constraint onchain_transfer_mint_shape
        check (mint_address is null or mint_address ~ '^[1-9A-HJ-NP-Za-km-z]{32,44}$'),
    constraint onchain_transfer_no_self_supersede
        check (supersedes_id is null or supersedes_id <> id),
    constraint onchain_transfer_rationale_required_when_superseding
        check (supersedes_id is null or length(btrim(coalesce(rationale, ''))) > 0)
);

comment on table mesta.onchain_transfer is
    'Append-only staging for normalized Solana transfers. Vendor-neutral rows; the Helius adapter''s raw response stays in helius_payload for lineage.';
comment on column mesta.onchain_transfer.external_id is
    '"<chain>:<signature>:<account>:<instruction-path>" — deterministic, so poller re-scans and webhook duplicates collapse on the unique key.';
comment on column mesta.onchain_transfer.commitment is
    'Always ''finalized'' (~32 rooted slots): the only Solana level that cannot roll back. The adapter filters before insert.';
comment on column mesta.onchain_transfer.mint_address is
    'Null means native SOL; otherwise the SPL/token-2022 mint. Never resolved here — promotion joins mesta.instrument.';
comment on column mesta.onchain_transfer.direction is
    'in/out relative to wallet, self for wallet-to-itself, fee for the network fee leg.';

create index onchain_transfer_wallet_slot_idx
    on mesta.onchain_transfer (chain, wallet, slot);
create index onchain_transfer_signature_idx
    on mesta.onchain_transfer (signature);
create index onchain_transfer_supersedes_idx
    on mesta.onchain_transfer (supersedes_id)
    where supersedes_id is not null;

create unique index onchain_transfer_source_external_key
    on mesta.onchain_transfer (source_system, external_id);

-- -----------------------------------------------------------------------------
-- Staging: observed balances. The recon module diffs the latest snapshot against the position
-- derived from instrument_flow. usd_value is provider valuation data — it never becomes a
-- ledger fact.
-- -----------------------------------------------------------------------------
create table mesta.onchain_balance_snapshot (
    id               uuid            primary key default gen_random_uuid(),
    external_id      text            not null,
    as_of            timestamptz     not null,
    chain            text            not null,
    wallet           text            not null,
    token_account    text,
    mint_address     text,
    amount_raw       numeric(38, 0)  not null,
    decimals         smallint        not null,
    usd_value        numeric(38, 10),
    source           text            not null,
    slot             bigint,
    recorded_at      timestamptz     not null default now(),
    supersedes_id    uuid            references mesta.onchain_balance_snapshot (id),
    rationale        text,
    source_system    text            not null,
    actor            text            not null,
    ingestion_run_id uuid            not null,
    correlation_id   uuid            not null,

    constraint onchain_balance_snapshot_source_known check (source in ('wallet-api', 'das', 'rpc')),
    constraint onchain_balance_snapshot_decimals_range check (decimals between 0 and 255),
    constraint onchain_balance_snapshot_amount_nonnegative check (amount_raw >= 0),
    constraint onchain_balance_snapshot_wallet_shape
        check (wallet ~ '^[1-9A-HJ-NP-Za-km-z]{32,44}$'),
    constraint onchain_balance_snapshot_no_self_supersede
        check (supersedes_id is null or supersedes_id <> id),
    constraint onchain_balance_snapshot_rationale_required_when_superseding
        check (supersedes_id is null or length(btrim(coalesce(rationale, ''))) > 0)
);

comment on table mesta.onchain_balance_snapshot is
    'Append-only observed balances per (wallet, mint) for reconciliation. A snapshot is a fact about what the provider reported, not about the position.';
comment on column mesta.onchain_balance_snapshot.usd_value is
    'Provider-reported USD mark. Valuation data only — token valuation methodology is a separate workstream and never enters the ledger.';
comment on column mesta.onchain_balance_snapshot.mint_address is
    'Null means the native SOL balance row.';

create index onchain_balance_snapshot_wallet_idx
    on mesta.onchain_balance_snapshot (chain, wallet, mint_address, as_of);

create unique index onchain_balance_snapshot_source_external_key
    on mesta.onchain_balance_snapshot (source_system, external_id);

-- -----------------------------------------------------------------------------
-- The token ledger. Mirrors ledger_event semantics — append-only, supersedes+rationale,
-- external idempotency key — but keyed by instrument and denominated in raw base units, so
-- fiat currency_code rules stay untouched. Token positions are derived by summing signed
-- flows; nothing writes a position row anywhere.
-- -----------------------------------------------------------------------------
create table mesta.instrument_flow (
    id               uuid            primary key default gen_random_uuid(),
    external_id      text            not null,
    instrument_id    uuid            not null references mesta.instrument (id),
    chain            text            not null,
    wallet           text            not null,
    token_account    text,
    flow_type        text            not null,
    amount_raw       numeric(38, 0)  not null,
    decimals         smallint        not null,
    occurred_at      timestamptz     not null,
    recorded_at      timestamptz     not null default now(),
    slot             bigint,
    signature        text,
    supersedes_id    uuid            references mesta.instrument_flow (id),
    rationale        text,
    source_system    text            not null,
    actor            text            not null,
    ingestion_run_id uuid            not null,
    correlation_id   uuid            not null,

    constraint instrument_flow_type_known check (flow_type in (
        'transfer-in',
        'transfer-out',
        'staking-reward',
        'airdrop',
        'unlock',
        'vesting-claim',
        'mint',
        'burn',
        'other'
    )),
    constraint instrument_flow_decimals_range check (decimals between 0 and 255),
    constraint instrument_flow_amount_nonnegative check (amount_raw >= 0),
    constraint instrument_flow_wallet_shape
        check (wallet ~ '^[1-9A-HJ-NP-Za-km-z]{32,44}$'),
    constraint instrument_flow_no_self_supersede
        check (supersedes_id is null or supersedes_id <> id),
    constraint instrument_flow_rationale_required_when_superseding
        check (supersedes_id is null or length(btrim(coalesce(rationale, ''))) > 0)
);

comment on table mesta.instrument_flow is
    'Append-only token ledger, the onchain counterpart of ledger_event. Position = signed sum over non-superseded flows per (instrument_id, wallet); never stored.';
comment on column mesta.instrument_flow.external_id is
    'Identical to the staging row''s external_id — promotion is replay-safe because the unique key refuses a second insert.';
comment on column mesta.instrument_flow.amount_raw is
    'Magnitude in base units; direction comes from flow_type, not a sign bit, so the value stays non-negative like ledger_event.monetary_amount.';
comment on column mesta.instrument_flow.flow_type is
    'Mirrors instrument-flow-type @values. transfer-in/out carry direction; staking-reward, airdrop, unlock, vesting-claim, mint are inbound; burn is outbound.';

create index instrument_flow_position_idx
    on mesta.instrument_flow (instrument_id, wallet, occurred_at);
create index instrument_flow_supersedes_idx
    on mesta.instrument_flow (supersedes_id)
    where supersedes_id is not null;

create unique index instrument_flow_source_external_key
    on mesta.instrument_flow (source_system, external_id);

-- Append-only enforcement on every table in this migration.
create trigger instrument_append_only
    before update or delete on mesta.instrument
    for each row execute function mesta.reject_mutation();
create trigger tracked_address_append_only
    before update or delete on mesta.tracked_address
    for each row execute function mesta.reject_mutation();
create trigger tracked_address_event_append_only
    before update or delete on mesta.tracked_address_event
    for each row execute function mesta.reject_mutation();
create trigger onchain_transfer_append_only
    before update or delete on mesta.onchain_transfer
    for each row execute function mesta.reject_mutation();
create trigger onchain_balance_snapshot_append_only
    before update or delete on mesta.onchain_balance_snapshot
    for each row execute function mesta.reject_mutation();
create trigger instrument_flow_append_only
    before update or delete on mesta.instrument_flow
    for each row execute function mesta.reject_mutation();

grant select, insert
    on mesta.instrument,
       mesta.tracked_address,
       mesta.tracked_address_event,
       mesta.onchain_transfer,
       mesta.onchain_balance_snapshot,
       mesta.instrument_flow
    to "${runtime_role}";
