-- V49__solana_history_cursor.sql
-- Resume point for the Solana history walk (#509).
--
-- The walk is newest-first and bounded per run. The highest staged slot is the wrong
-- watermark: a partial page stages the tip, and a webhook can stage that tip before any
-- backfill, so `filters.slot.gt` skips the older gap. This row is the walk's own mark.
-- It is mutable current state, like tenant_setting — not a ledger fact — so the runtime
-- role may update it. Deletes are not granted; unwatching an address leaves the mark so
-- a later watch resumes instead of skipping history it already scanned.
--
-- No tenant column. Ingestion polls under TenantScope.All (`app.tenant_ids = '*'`) and
-- rls_admits(NULL) is that bypass, the same rule V27 gives a platform-shared address.
-- A request scoped to one tenant does not read or write another wallet's resume point.

create table octo.solana_history_cursor (
    chain            text        not null,
    address          text        not null,
    floor_slot       bigint,
    resume_token     text,
    pending_tip_slot bigint,
    updated_at       timestamptz not null default now(),

    primary key (chain, address),
    foreign key (chain, address) references octo.tracked_address (chain, address),
    constraint solana_history_cursor_floor_nonneg check (floor_slot is null or floor_slot >= 0),
    constraint solana_history_cursor_tip_nonneg check (pending_tip_slot is null or pending_tip_slot >= 0),
    constraint solana_history_cursor_token_len check (resume_token is null or length(resume_token) between 1 and 512),
    constraint solana_history_cursor_tip_with_token check (pending_tip_slot is null or resume_token is not null)
);

comment on table octo.solana_history_cursor is
    'Solana history-walk resume point (#509). Not derived from staged slots: a partial page or a webhook must not move it past unscanned history.';
comment on column octo.solana_history_cursor.floor_slot is
    'Last slot a finished walk covered. The next poll passes it as filters.slot.gt. Null until the first walk finishes.';
comment on column octo.solana_history_cursor.resume_token is
    'Helius pagination token for a newest-first walk that stopped early. Null when the walk is caught up and floor_slot is the watermark.';
comment on column octo.solana_history_cursor.pending_tip_slot is
    'Newest slot of the open walk. Becomes floor_slot when resume_token runs out, so the floor does not jump to a later webhook.';

grant select, insert, update on octo.solana_history_cursor to "${runtime_role}";

alter table octo.solana_history_cursor enable row level security;
create policy tenant_scope on octo.solana_history_cursor
    using (octo.rls_admits(null::uuid))
    with check (octo.rls_admits(null::uuid));
