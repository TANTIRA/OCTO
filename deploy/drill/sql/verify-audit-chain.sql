-- Verify the octo.audit_event hash chain against the database itself.
--
-- This is a SQL mirror of `verifyAuditChain` (modules/workflow/.../AuditChain.kt) over the
-- same bytes as `octo.audit_event_canonical` (db/migrations/V6__audit_event.sql), so a
-- restore can be proven intact without booting the JVM. It is not a replacement for the
-- Kotlin check on a live system — the application is still the authority — but it runs
-- against a restored database that has no application attached, which is exactly the
-- moment the restore runbook's §7 checklist needs it.
--
-- Byte-for-byte rules it reproduces:
--   * canonical row = for each of the nine fields in column order, "<utf8 byte length>:<field>",
--     concatenated with no separator;
--   * timestamps are epoch microseconds as integers, so the session time zone cannot change a hash;
--   * details is the jsonb text form;
--   * link hash = sha256(prev_hash || canonical row), genesis prev_hash = 32 zero bytes.
--
-- Returns: no rows when the chain verifies. One row naming the first break when it does not.
-- Run with: psql -v ON_ERROR_STOP=1 -f verify-audit-chain.sql

with recursive r as (
    select a.seq, a.occurred_at, a.recorded_at, a.actor, a.action,
           a.subject_type, a.subject_id, a.correlation_id, a.details,
           a.prev_hash, a.hash,
           -- field_len/field_val carry the eight fields already rendered
           array[
               octet_length(convert_to(a.seq::text, 'UTF8'))::text || ':' || a.seq::text,
               octet_length(convert_to(((extract(epoch from a.occurred_at) * 1000000)::bigint)::text, 'UTF8'))::text
                   || ':' || ((extract(epoch from a.occurred_at) * 1000000)::bigint)::text,
               octet_length(convert_to(((extract(epoch from a.recorded_at) * 1000000)::bigint)::text, 'UTF8'))::text
                   || ':' || ((extract(epoch from a.recorded_at) * 1000000)::bigint)::text,
               octet_length(convert_to(a.actor, 'UTF8'))::text || ':' || a.actor,
               octet_length(convert_to(a.action, 'UTF8'))::text || ':' || a.action,
               octet_length(convert_to(a.subject_type, 'UTF8'))::text || ':' || a.subject_type,
               octet_length(convert_to(a.subject_id, 'UTF8'))::text || ':' || a.subject_id,
               octet_length(convert_to(a.correlation_id::text, 'UTF8'))::text || ':' || a.correlation_id::text
           ] as parts,
           a.details::text as details_text
    from octo.audit_event a
    where a.seq = 1

    union all

    select n.seq, n.occurred_at, n.recorded_at, n.actor, n.action,
           n.subject_type, n.subject_id, n.correlation_id, n.details,
           n.prev_hash, n.hash,
           array[
               octet_length(convert_to(n.seq::text, 'UTF8'))::text || ':' || n.seq::text,
               octet_length(convert_to(((extract(epoch from n.occurred_at) * 1000000)::bigint)::text, 'UTF8'))::text
                   || ':' || ((extract(epoch from n.occurred_at) * 1000000)::bigint)::text,
               octet_length(convert_to(((extract(epoch from n.recorded_at) * 1000000)::bigint)::text, 'UTF8'))::text
                   || ':' || ((extract(epoch from n.recorded_at) * 1000000)::bigint)::text,
               octet_length(convert_to(n.actor, 'UTF8'))::text || ':' || n.actor,
               octet_length(convert_to(n.action, 'UTF8'))::text || ':' || n.action,
               octet_length(convert_to(n.subject_type, 'UTF8'))::text || ':' || n.subject_type,
               octet_length(convert_to(n.subject_id, 'UTF8'))::text || ':' || n.subject_id,
               octet_length(convert_to(n.correlation_id::text, 'UTF8'))::text || ':' || n.correlation_id::text
           ] as parts,
           n.details::text as details_text
    from octo.audit_event n
    join r on n.seq = r.seq + 1
    where n.prev_hash = r.hash
),
canonical_rows as (
    -- All nine fields get the "<byte length>:<field>" treatment, details included: V6 builds
    -- the array with details as the ninth element, so it is length-prefixed like the rest.
    select seq, prev_hash, hash,
           convert_to(
               array_to_string(parts, '')
               || octet_length(convert_to(details_text, 'UTF8'))::text || ':' || details_text,
               'UTF8') as canonical
    from r
),
recomputed as (
    select seq, prev_hash, hash, canonical,
           case
               when seq = 1 then prev_hash = decode(repeat('00', 32), 'hex')
               else prev_hash = lag(hash) over (order by seq)
           end as prev_ok
    from canonical_rows
),
checked as (
    select seq,
           prev_ok,
           hash = sha256(prev_hash || canonical) as hash_ok,
           -- A walk that starts at seq 1 walking a chain whose first row points at a
           -- predecessor that is not genesis means rows before it were removed; without this
           -- the walk happily verifies the surviving suffix and calls a truncated log intact.
           (seq = 1 and prev_ok) as genesis_anchored
    from recomputed
),
first_break as (
    -- There may be rows *after* the break that still verify in isolation; walk to the first.
    select min(seq) as seq
    from checked
    where not prev_ok or not hash_ok
)
select
    case
        when (select count(*) from octo.audit_event) = 0 then 'EMPTY'
        when (select count(*) from octo.audit_event) <> (select count(*) from r) then 'BROKEN'
        when (select seq from first_break) is not null then 'BROKEN'
        when not coalesce((select genesis_anchored from checked where seq = 1), false) then 'BROKEN'
        else 'INTACT'
    end as chain_state,
    coalesce(
        (select case
                    when not prev_ok then 'prev_hash does not match the previous row''s hash'
                    else 'hash does not match the row''s contents'
                end
         from checked
         where seq = (select seq from first_break)),
        case
            when (select count(*) from octo.audit_event) = 0 then 'no audit rows to verify'
            when (select count(*) from octo.audit_event) <> (select count(*) from r) then 'a row is missing or out of order'
            when not coalesce((select genesis_anchored from checked where seq = 1), false)
                then 'the oldest surviving row is not anchored at genesis: rows before it were removed'
            else 'chain verified end to end'
        end
    ) as detail,
    (select count(*) from octo.audit_event) as rows_checked,
    (select coalesce(max(seq), 0) from octo.audit_event) as head_seq;
