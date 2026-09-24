-- V6__audit_event.sql
-- Tamper-evident, append-only audit log (issue #51, platform P2 on #6).
--
-- Grounded in:
--   data-security-governance.md  "Audit records are tamper-evident, access-restricted, time-synchronized,
--                                 and retained according to policy"
--   docs/system-design.md        audit lives in PostgreSQL next to workflow state
--
-- Tamper-evident: each row stores hash = sha256(prev_hash || canonical row), so changing, removing or
-- reordering a row breaks its link; verifyAuditChain in modules/workflow finds the first broken one.
-- Access-restricted: a SECURITY DEFINER trigger builds the chain, so the runtime role needs INSERT only
-- and can never read the log back. Time-synchronized: recorded_at comes from the database clock, and
-- seq, prev_hash and hash are always assigned here, whatever the caller sends.
--
-- Known ceiling: a superuser can rewrite the chain consistently, or cut off its newest rows. Anchoring
-- the head hash outside the database (the OpenTelemetry audit sink in system-design.md) is the follow-up.
-- Must apply after V5 (issue #50); both are new, so Flyway orders them on a fresh database.

create table mesta.audit_event (
    seq            bigint      primary key,
    occurred_at    timestamptz not null,
    recorded_at    timestamptz not null,
    actor          text        not null,
    action         text        not null,
    subject_type   text        not null,
    subject_id     text        not null,
    correlation_id uuid        not null,
    details        jsonb       not null default '{}'::jsonb,
    prev_hash      bytea       not null,
    hash           bytea       not null,

    constraint audit_event_fields_present check (
        length(btrim(actor)) > 0 and length(btrim(action)) > 0
        and length(btrim(subject_type)) > 0 and length(btrim(subject_id)) > 0),
    -- References only (ids, hashes, versions), never sensitive payloads: governance forbids them in logs.
    constraint audit_event_details_object check (jsonb_typeof(details) = 'object'),
    constraint audit_event_sha256 check (octet_length(prev_hash) = 32 and octet_length(hash) = 32)
);

comment on table mesta.audit_event is
    'Hash-chained, append-only audit log. Written through a trigger that assigns seq, recorded_at and the chain.';

-- The exact bytes hashed for a row; verifyAuditChain mirrors it. Each field is its UTF-8 byte length, a
-- colon and the field, in column order. Timestamps are epoch microseconds, so the session time zone cannot
-- change a hash, and details is the jsonb text form, which is canonical for a given value.
create function mesta.audit_event_canonical(
    seq bigint, occurred_at timestamptz, recorded_at timestamptz, actor text, action text,
    subject_type text, subject_id text, correlation_id uuid, details jsonb
) returns bytea
    language sql
    stable
    strict
    set search_path = pg_catalog, pg_temp
as $$
    select convert_to(string_agg(octet_length(convert_to(f, 'UTF8'))::text || ':' || f, '' order by n), 'UTF8')
    from unnest(array[
        seq::text,
        (extract(epoch from occurred_at) * 1000000)::bigint::text,
        (extract(epoch from recorded_at) * 1000000)::bigint::text,
        actor, action, subject_type, subject_id, correlation_id::text, details::text
    ]) with ordinality as field(f, n)
$$;

create function mesta.audit_event_chain() returns trigger
    language plpgsql
    security definer
    set search_path = pg_catalog, pg_temp
as $$
declare
    head_seq  bigint;
    head_hash bytea;
begin
    -- ponytail: one lock serializes every audit write; move to per-stream chains if audit volume needs parallel writers.
    perform pg_advisory_xact_lock(hashtextextended('mesta.audit_event', 0));
    select e.seq, e.hash into head_seq, head_hash from mesta.audit_event e order by e.seq desc limit 1;
    new.seq := coalesce(head_seq, 0) + 1;
    new.prev_hash := coalesce(head_hash, decode(repeat('00', 32), 'hex'));
    new.recorded_at := clock_timestamp();
    new.hash := sha256(new.prev_hash || mesta.audit_event_canonical(
        new.seq, new.occurred_at, new.recorded_at, new.actor, new.action,
        new.subject_type, new.subject_id, new.correlation_id, new.details));
    return new;
end;
$$;

create trigger audit_event_chain
    before insert on mesta.audit_event
    for each row
    execute function mesta.audit_event_chain();

create trigger audit_event_append_only
    before update or delete on mesta.audit_event
    for each row
    execute function mesta.reject_mutation();

-- Row triggers do not see TRUNCATE, which would erase the whole chain without breaking a link.
create trigger audit_event_no_truncate
    before truncate on mesta.audit_event
    for each statement
    execute function mesta.reject_mutation();

grant insert on mesta.audit_event to "${runtime_role}";
