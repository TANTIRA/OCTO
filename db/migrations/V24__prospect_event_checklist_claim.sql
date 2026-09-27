-- V24__prospect_event_checklist_claim.sql
-- #201 review follow-up: a due-diligence landing now names the evidence checklist it claims —
-- the same lineage V19 gives `invested` and its approval task. Recording the claim lets a
-- transition that loses its append race read back whether the winner's landing runs on the
-- task it minted, instead of guessing "claimed" from the replayed stage and keeping an
-- orphan no one can ever close.
--
-- V19's check allowed task_id only on 'invested' rows, so it comes down in favor of the
-- lineage rule: 'invested' still requires the id, 'advanced' may carry it only on a
-- due-diligence landing, and 'passed' never does. Rows already written satisfy the new
-- check — earlier landings simply never recorded their checklist, which readers treat as
-- an unrecorded claim, never as proof the task was unclaimed.

alter table mesta.prospect_event
    drop constraint prospect_event_task_iff_invested,
    add constraint prospect_event_task_lineage
        check (
            (event_type = 'invested' and task_id is not null)
            or (event_type = 'advanced' and (task_id is null or stage_to = 'due-diligence'))
            or (event_type = 'passed' and task_id is null)
        );
