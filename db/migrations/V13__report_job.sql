-- V13__report_job.sql
-- Report jobs behind the report service (issue #105, #6 slice 7, Marquee report service).
--
-- Grounded in:
--   docs/system-design.md          report artifacts live in PostgreSQL next to workflow state
--   #6 decision 2 (2026-09-25)     every new domain table carries tenant_id from its first migration
--   V7                              transition rules that must hold even when a caller bypasses Kotlin live in a trigger
--
-- A job is process state, not a fact, so unlike the fact tables it is updated in place: status moves
-- new -> executing -> done | error, once, and nothing else on the row ever changes. The trigger enforces
-- that; the runtime role may update only the columns a transition writes. Outbound release goes through
-- a workflow_task of kind 'approval' (approval_task_id); the gate itself is a later slice.
-- Numbered V13 after V10 (#110), V11 (#126) and V12 (#133); must merge after them or be renumbered.

create table mesta.report_job (
    id                   uuid        primary key default gen_random_uuid(),
    tenant_id            uuid        not null references mesta.tenant (id),
    report_type          text        not null,
    position_source_type text        not null,
    position_source_id   text        not null,
    measures             text[]      not null default '{}',
    parameters           jsonb       not null default '{}'::jsonb,
    status               text        not null default 'new',
    requested_by         text        not null,
    result               jsonb,
    error                text,
    artifact_sha256      text,
    approval_task_id     uuid        references mesta.workflow_task (id),
    correlation_id       uuid        not null,
    created_at           timestamptz not null default now(),
    updated_at           timestamptz not null default now(),

    constraint report_job_type_known check (report_type in ('performance', 'exposure', 'attribution')),
    constraint report_job_status_known check (status in ('new', 'executing', 'done', 'error')),
    constraint report_job_source_named
        check (length(btrim(position_source_type)) > 0 and length(btrim(position_source_id)) > 0),
    constraint report_job_requester_named check (length(btrim(requested_by)) > 0),
    constraint report_job_measures_no_empty check ('' <> all(measures) and array_position(measures, null) is null),
    constraint report_job_json_objects check (
        jsonb_typeof(parameters) = 'object' and (result is null or jsonb_typeof(result) = 'object')),
    constraint report_job_outcome_matches_status check (
        (status = 'done') = (result is not null) and (status = 'error') = (error is not null and length(btrim(error)) > 0)),
    constraint report_job_artifact_sha256_shape check (artifact_sha256 is null or artifact_sha256 ~ '^[0-9a-f]{64}$'),
    constraint report_job_artifact_only_when_done check (artifact_sha256 is null or status = 'done')
);

comment on table mesta.report_job is
    'Report jobs: new -> executing -> done | error, enforced by report_job_transition. The request columns never change after insert.';

create function mesta.report_job_transition() returns trigger
    language plpgsql
    set search_path = pg_catalog, pg_temp
as $$
begin
    if new.status is distinct from old.status then
        if not (old.status = 'new' and new.status = 'executing'
                or old.status = 'executing' and new.status in ('done', 'error')) then
            raise exception 'report job % cannot move from % to %', old.id, old.status, new.status
                using errcode = 'check_violation';
        end if;
    elsif old.status in ('new', 'done', 'error') and (new.approval_task_id is not distinct from old.approval_task_id) then
        raise exception 'report job % is % and cannot be changed', old.id, old.status using errcode = 'check_violation';
    end if;
    if new.approval_task_id is distinct from old.approval_task_id and (old.approval_task_id is not null or old.status <> 'done') then
        raise exception 'report job % gets one approval task, after it is done', old.id using errcode = 'check_violation';
    end if;
    if (new.id, new.tenant_id, new.report_type, new.position_source_type, new.position_source_id, new.measures,
        new.parameters, new.requested_by, new.correlation_id, new.created_at)
       is distinct from
       (old.id, old.tenant_id, old.report_type, old.position_source_type, old.position_source_id, old.measures,
        old.parameters, old.requested_by, old.correlation_id, old.created_at) then
        raise exception 'the request columns of report job % are immutable', old.id using errcode = 'check_violation';
    end if;
    new.updated_at := clock_timestamp();
    return new;
end;
$$;

create trigger report_job_transition
    before update on mesta.report_job
    for each row
    execute function mesta.report_job_transition();

create trigger report_job_no_delete
    before delete on mesta.report_job
    for each row
    execute function mesta.reject_mutation();

create trigger report_job_no_truncate
    before truncate on mesta.report_job
    for each statement
    execute function mesta.reject_mutation();

create index report_job_queue on mesta.report_job (created_at) where status = 'new';

grant select, insert on mesta.report_job to "${runtime_role}";
grant update (status, result, error, artifact_sha256, approval_task_id, updated_at) on mesta.report_job to "${runtime_role}";
