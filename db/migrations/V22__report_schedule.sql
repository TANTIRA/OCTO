-- V22__report_schedule.sql
-- Report schedules (issue #211, #6 gap map: scheduled reporting). report_job executes a submitted
-- request; nothing submits one on a cadence. A schedule is mutable operational config — the jobs it
-- submits carry the full request + provenance, so the schedule itself is updated in place.
--
-- Concurrency: due = active and next_run_at <= now and claimed_until is null or lapsed. The runner
-- claims by stamping claimed_until (a lease), submits the job, then markRun writes the real
-- next_run_at and clears the lease. A crashed runner never double-fires: the row re-due's only
-- after the lease lapses.
-- Numbered V22 after V18-V21 (the #201 prospect stack); must merge after them or be renumbered.

create table mesta.report_schedule (
    id                   uuid        primary key default gen_random_uuid(),
    tenant_id            uuid        not null references mesta.tenant (id),
    name                 text        not null,
    report_type          text        not null,
    position_source_type text        not null,
    position_source_id   text        not null,
    measures             text[]      not null default '{}',
    parameters           jsonb       not null default '{}'::jsonb,
    cron                 text        not null,
    next_run_at          timestamptz not null,
    active               boolean     not null default true,
    claimed_until        timestamptz,
    created_at           timestamptz not null default now(),
    updated_at           timestamptz not null default now(),

    constraint report_schedule_name_named check (length(btrim(name)) > 0),
    constraint report_schedule_type_known check (report_type in ('performance', 'exposure', 'attribution')),
    constraint report_schedule_source_named
        check (length(btrim(position_source_type)) > 0 and length(btrim(position_source_id)) > 0),
    constraint report_schedule_measures_no_empty check ('' <> all(measures) and array_position(measures, null) is null),
    constraint report_schedule_parameters_object check (jsonb_typeof(parameters) = 'object'),
    constraint report_schedule_cron_named check (length(btrim(cron)) > 0)
);

comment on table mesta.report_schedule is
    'Report schedules: a cron + a report template per tenant. The runner claims due rows via
     claimed_until, submits report_job, then advances next_run_at. Mutable config; the submitted
     jobs carry the audit trail.';

create function mesta.report_schedule_guard() returns trigger
    language plpgsql
    set search_path = pg_catalog, pg_temp
as $$
begin
    if (new.id, new.tenant_id, new.created_at) is distinct from (old.id, old.tenant_id, old.created_at) then
        raise exception 'the identity columns of report schedule % are immutable', old.id using errcode = 'check_violation';
    end if;
    new.updated_at := clock_timestamp();
    return new;
end;
$$;

create trigger report_schedule_guard
    before update on mesta.report_schedule
    for each row
    execute function mesta.report_schedule_guard();

create trigger report_schedule_no_delete
    before delete on mesta.report_schedule
    for each row
    execute function mesta.reject_mutation();

create trigger report_schedule_no_truncate
    before truncate on mesta.report_schedule
    for each statement
    execute function mesta.reject_mutation();

create index report_schedule_due on mesta.report_schedule (next_run_at) where active;

grant select, insert on mesta.report_schedule to "${runtime_role}";
grant update (name, report_type, position_source_type, position_source_id, measures, parameters,
              cron, next_run_at, active, claimed_until, updated_at) on mesta.report_schedule to "${runtime_role}";
