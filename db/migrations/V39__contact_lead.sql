-- V39__contact_lead.sql
-- Lead capture for the public landing-page contact form (#315). A submitter is
-- pre-tenant by definition, so the table carries no tenant_id and V27's
-- row-level security deliberately does not cover it — like workflow_task,
-- access binds at the API edge: POST /api/v1/contact is insert-only and no
-- read path exists yet (a lead-management surface is a follow-up).
--
-- PII lives here (name, email, phone, IP) — the table is append-only so a
-- submission is never rewritten, and retention/deletion is an ops runbook item
-- (backup.sh archives; a privacy-erasure path lands with the read surface).

create table octo.contact_lead (
    id         uuid        primary key default gen_random_uuid(),
    email      text        not null,
    first_name text        not null,
    last_name  text        not null,
    firm       text        not null,
    role       text        not null,
    aum_band   text        not null,
    phone      text,
    message    text,
    source_ip  text,
    created_at timestamptz not null default now(),

    constraint contact_lead_fields_named check (
        length(btrim(email)) > 0 and length(btrim(first_name)) > 0 and
        length(btrim(last_name)) > 0 and length(btrim(firm)) > 0 and
        length(btrim(role)) > 0 and length(btrim(aum_band)) > 0
    ),
    constraint contact_lead_source_ip_bounded check (length(source_ip) <= 255)
);

comment on table octo.contact_lead is
    'Public contact-form submissions. Pre-tenant: no tenant_id, no RLS (V27 exclusion list).
     Insert-only — the runtime role cannot read leads back.';

-- Insert-only grant: the api writes submissions; nothing reads them yet, and the
-- anonymous caller the api serves must never reach even its own row back.
grant insert on octo.contact_lead to "${runtime_role}";
