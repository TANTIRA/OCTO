-- V31__tenant_setting.sql
-- Per-tenant configuration: the settings surface the recon/report runners, feature flags and
-- quota knobs read, where application.yml can only speak for the whole deployment (tenancy
-- megaplan slice C).
--
-- Grounded in:
--   V8__tenant_access.sql                the boundary a setting scopes to
--   V27__tenant_row_level_security.sql   rls_admits policy, same shape as every tenant table
--   data-security-governance.md          privileged configuration changes are audited — every
--                                        write also lands an audit_event row in the same
--                                        transaction (JdbcTenantSettingsStore.put)
--
-- Deliberately a mutable upsert table, not an event log: a setting is current state, and the
-- audit trail already lives in audit_event. Runtime role needs update for the upsert; delete is
-- not granted — clearing a key is a setting of null, not a removal, so the key's history stays.

create table octo.tenant_setting (
    tenant_id       uuid        not null references octo.tenant (id),
    key             text        not null,
    value           jsonb       not null,
    recorded_at     timestamptz not null default now(),
    source_system   text        not null,
    correlation_id  uuid        not null,

    primary key (tenant_id, key),
    constraint tenant_setting_key_shape check (key ~ '^[a-z0-9][a-z0-9_.-]{0,126}$')
);

comment on table octo.tenant_setting is
    'Current per-tenant configuration (mutable, audited via audit_event on every write). Values are jsonb so a scalar, list or object setting never forces a schema change.';
comment on column octo.tenant_setting.key is
    'Snake/dotted key — e.g. rate_limit_per_minute, features.screening_dd. Known keys are registered in TenantSettingKeys.';

grant select, insert, update on octo.tenant_setting to "${runtime_role}";

alter table octo.tenant_setting enable row level security;
create policy tenant_scope on octo.tenant_setting
    using (octo.rls_admits(tenant_id))
    with check (octo.rls_admits(tenant_id));
