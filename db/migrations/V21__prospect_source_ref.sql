-- #201: CRM/referral intake needs idempotent registration — a re-sync of the same external record
-- must not mint a second prospect. source_ref is the source system's own identifier; nullable for
-- manual registrations, and the unique partial index dedupes only when it is present.

alter table mesta.prospect
    add column source_ref text;

create unique index prospect_source_ref_unique
    on mesta.prospect (tenant_id, source, source_ref)
    where source_ref is not null;

comment on column mesta.prospect.source_ref is
    'External identifier in the source system (CRM record id, referral code). Unique per tenant+source
     so an adapter re-import is a no-op, not a duplicate prospect.';
