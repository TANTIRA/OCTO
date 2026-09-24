-- V8__tenant_access.sql
-- Multi-tenant user accessibility: which users may act inside which organization, in which role.
--
-- Grounded in:
--   data-security-governance.md  "Scope authorization by organization"; "Enforce permissions in the
--                                 API and data layer"; "revoke access promptly after role change or
--                                 termination"; "No person may approve their own privileged access"
--   docs/system-design.md §2.5   the resource server authenticates; RBAC/ABAC authorization is the
--                                 layer module services enforce on top of it
--   V1__init.sql                 deferred "the organization/fund scoping model" until it was decided;
--                                 this migration is that decision's first slice
--   ADR-0002                     domain schemas stay isolated from auth.users; RLS is defense in
--                                 depth behind the API layer, not a replacement for it
--
-- A tenant is the organization a slice of the deployment's data belongs to: the operating firm, or
-- an external LP/GP organization whose users get scoped access. user_id is the Supabase Auth subject
-- (the JWT sub) — deliberately no FK to auth.users, because Supabase internal schemas stay isolated.
--
-- Membership state is never stored: it is replayed from mesta.tenant_member_event, the way a task's
-- state is replayed from workflow_task_event (V5). Every grant, role change and revocation is an
-- auditable row, and a revoked member loses access on the next read. The trigger repeats the rules
-- that must hold even when a caller bypasses the Kotlin state machine, under a per-member advisory
-- lock so two writers to one membership serialize.
--
-- Deliberately not in this migration: tenant_id columns on the domain tables and the RLS policies
-- over them — entity-level scoping needs the domain model's tenant column first, and the API layer
-- remains the enforcement point either way (ADR-0002).

create table mesta.tenant (
    id              uuid        primary key default gen_random_uuid(),
    slug            text        not null,
    display_name    text        not null,
    created_at      timestamptz not null default now(),
    source_system   text        not null,
    correlation_id  uuid        not null,

    constraint tenant_slug_shape check (slug ~ '^[a-z0-9][a-z0-9-]{0,62}$'),
    constraint tenant_display_name_named check (length(btrim(display_name)) > 0)
);

comment on table mesta.tenant is
    'An organization whose data and users form one access boundary (data-security-governance.md). Immutable: a rename is a new governed fact, not an edit.';

create unique index tenant_slug_key on mesta.tenant (slug);

create table mesta.tenant_member (
    tenant_id       uuid        not null references mesta.tenant (id),
    user_id         uuid        not null,
    created_at      timestamptz not null default now(),
    source_system   text        not null,
    correlation_id  uuid        not null,

    primary key (tenant_id, user_id)
);

comment on table mesta.tenant_member is
    'Immutable membership header for one user in one tenant. Access itself is derived from mesta.tenant_member_event.';
comment on column mesta.tenant_member.user_id is
    'The Supabase Auth subject (JWT sub). No FK to auth.users: Supabase schemas stay isolated from mesta (ADR-0002).';

create table mesta.tenant_member_event (
    id              uuid        primary key default gen_random_uuid(),
    seq             bigint      generated always as identity,
    tenant_id       uuid        not null,
    user_id         uuid        not null,
    event_type      text        not null,
    role            text,
    actor           text        not null,
    rationale       text,
    occurred_at     timestamptz not null,
    correlation_id  uuid        not null,

    foreign key (tenant_id, user_id) references mesta.tenant_member (tenant_id, user_id),

    constraint tenant_member_event_type_known check (event_type in ('granted', 'role-changed', 'revoked')),
    -- Coarse RBAC roles; entity-level ABAC scoping is a follow-up (see header).
    constraint tenant_member_event_role_known
        check (role is null or role in ('admin', 'analyst', 'approver', 'viewer')),
    constraint tenant_member_event_role_iff_access
        check ((event_type = 'revoked') = (role is null)),
    constraint tenant_member_event_rationale_required_on_revoke
        check (event_type <> 'revoked' or length(btrim(coalesce(rationale, ''))) > 0),
    constraint tenant_member_event_actor_named check (length(btrim(actor)) > 0)
);

comment on table mesta.tenant_member_event is
    'Append-only access history. actor is the grantor''s user id; a revocation carries its rationale. The latest event fully determines current access.';
comment on column mesta.tenant_member_event.seq is
    'Append order. Current access is the latest event by seq: occurred_at can tie.';

create index tenant_member_event_member_idx
    on mesta.tenant_member_event (tenant_id, user_id, seq);

-- The membership rules in the database, mirroring Membership.next() in modules/api: the first event
-- must be a grant, a grant lands only when no access is active, role changes and revocations need an
-- active membership, events are in time order, and nobody grants or changes their own access.
create function mesta.tenant_member_event_rules() returns trigger
    language plpgsql
as $$
declare
    latest_type text;
    latest_at   timestamptz;
    active      boolean;
begin
    -- One writer per membership at a time. JdbcAccessStore takes the same lock before it replays and appends.
    perform pg_advisory_xact_lock(
        hashtextextended('mesta.tenant_member:' || new.tenant_id::text || ':' || new.user_id::text, 0));

    select e.event_type, e.occurred_at
      into latest_type, latest_at
      from mesta.tenant_member_event e
     where e.tenant_id = new.tenant_id and e.user_id = new.user_id
     order by e.seq desc
     limit 1;
    active := latest_type in ('granted', 'role-changed');

    if new.event_type = 'granted' and coalesce(active, false) then
        raise exception 'user % already has access to tenant %; change the role instead', new.user_id, new.tenant_id
            using errcode = 'check_violation';
    end if;
    if new.event_type in ('role-changed', 'revoked') and not coalesce(active, false) then
        raise exception 'user % has no active membership in tenant %', new.user_id, new.tenant_id
            using errcode = 'check_violation';
    end if;
    if new.occurred_at < latest_at then
        raise exception 'membership events of % in tenant % must be in time order', new.user_id, new.tenant_id
            using errcode = 'check_violation';
    end if;
    -- data-security-governance.md: "No person may approve their own privileged access."
    if new.event_type in ('granted', 'role-changed') and new.actor = new.user_id::text then
        raise exception 'segregation of duties: % cannot grant or change their own access', new.user_id
            using errcode = 'check_violation';
    end if;
    return new;
end;
$$;

comment on function mesta.tenant_member_event_rules() is
    'Enforces Membership.next(): valid transitions, time order, and segregation of duties on access grants.';

create trigger tenant_member_event_rules
    before insert on mesta.tenant_member_event
    for each row
    execute function mesta.tenant_member_event_rules();

create trigger tenant_append_only
    before update or delete on mesta.tenant
    for each row
    execute function mesta.reject_mutation();

create trigger tenant_member_append_only
    before update or delete on mesta.tenant_member
    for each row
    execute function mesta.reject_mutation();

create trigger tenant_member_event_append_only
    before update or delete on mesta.tenant_member_event
    for each row
    execute function mesta.reject_mutation();

grant select, insert on mesta.tenant, mesta.tenant_member, mesta.tenant_member_event to "${runtime_role}";
