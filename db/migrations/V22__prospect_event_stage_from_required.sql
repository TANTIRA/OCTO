-- V22__prospect_event_stage_from_required.sql
-- #201 review: mesta.prospect_event.stage_from is written on every event row — the store records
-- the replayed stage the transition claims to leave. V18 allowed NULL for a "registration event"
-- that cannot exist (event_type only admits 'advanced', 'passed', 'invested', none of which can
-- carry NULL): a hand-written 'advanced' or 'passed' row with stage_from NULL passed every check
-- and then crashed the Kotlin replay (`stageFrom!!`) on every load, history and append — and, the
-- table being append-only, could never be repaired. The same hole admitted stage_from values of
-- 'invested'/'passed', which no event can legitimately leave (terminal stages accept nothing).
--
-- The database now repeats the per-row invariant the machine relies on, like V5 does for tasks:
-- every event names a real, non-terminal stage it leaves. The narrowed domain list alone cannot
-- express that — a CHECK constraint treats a NULL result as satisfied, so `x in (...)` never
-- excludes NULL; the columns themselves must be required. stage_to gets the same treatment: the
-- V18 constraint already allowed NULL there, and a NULL stage_to crashes replay identically.
--
-- Safe to tighten: the only writer is JdbcProspectStore.insertEvent, which always stores the
-- replayed stage — an open stage — and a non-null target.

alter table mesta.prospect_event
    alter column stage_from set not null,
    alter column stage_to set not null,
    drop constraint prospect_event_stages_known,
    add constraint prospect_event_stages_known check (
        stage_from in ('sourced', 'screening', 'due-diligence', 'ic-review')
        and stage_to in ('screening', 'due-diligence', 'ic-review', 'invested', 'passed')
    );

comment on column mesta.prospect_event.stage_from is
    'The replayed stage the transition claims to leave — required; the state machine verifies it equals the true stage.';
comment on column mesta.prospect_event.stage_to is
    'The stage the transition lands in — required.';
