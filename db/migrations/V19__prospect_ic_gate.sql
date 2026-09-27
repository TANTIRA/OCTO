-- #201: an `invested` event carries the workflow_task that approved it. The prospect header stays
-- immutable — the link lives on the append-only event, which already holds actor/rationale/provenance.
-- The foreign key guarantees the named task exists; that it is APPROVED for this prospect is checked
-- at write time (api → workflow_task replay), mirroring report_job.approval_task_id's two-step gate.

alter table mesta.prospect_event
    add column task_id uuid references mesta.workflow_task (id),
    add constraint prospect_event_task_iff_invested
        check ((event_type = 'invested') = (task_id is not null));
