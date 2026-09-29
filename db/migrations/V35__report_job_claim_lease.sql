alter table octo.report_job
    add column claim_token uuid,
    add column claimed_until timestamptz;

update octo.report_job
set claim_token = gen_random_uuid(), claimed_until = '-infinity'::timestamptz
where status = 'executing';

alter table octo.report_job add constraint report_job_claim_consistent
    check ((status = 'executing') = (claim_token is not null and claimed_until is not null));

create or replace function octo.report_job_transition() returns trigger
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
        if old.status = 'executing' and old.claimed_until <= clock_timestamp() then
            raise exception 'report job % lease expired', old.id using errcode = 'check_violation';
        end if;
    elsif old.status in ('new', 'done', 'error') and (new.approval_task_id is not distinct from old.approval_task_id) then
        raise exception 'report job % is % and cannot be changed', old.id, old.status using errcode = 'check_violation';
    end if;
    if old.status = 'executing' and new.status = 'executing' then
        if new.claim_token is distinct from old.claim_token and old.claimed_until > clock_timestamp() then
            raise exception 'report job % lease is held', old.id using errcode = 'check_violation';
        end if;
        if new.claim_token is not distinct from old.claim_token and new.claimed_until <= old.claimed_until then
            raise exception 'report job % lease must advance', old.id using errcode = 'check_violation';
        end if;
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

create index report_job_expired_claim on octo.report_job (claimed_until)
    where status = 'executing';

grant update (claim_token, claimed_until) on octo.report_job to "${runtime_role}";
