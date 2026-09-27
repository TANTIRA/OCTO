-- V23__workflow_task_subject_index.sql
-- #201 review: every deduplicated task open reads mesta.workflow_task by (subject_type, subject_id)
-- — JdbcTaskStore.subjectTaskIds, run inside the per-subject advisory lock openUnlessOpen takes —
-- and V5 never indexed it, so each open seq-scans the whole table while holding that lock.
-- created_at rides along so the creation-order read the store asks for sorts inside the index.

create index workflow_task_subject_idx
    on mesta.workflow_task (subject_type, subject_id, created_at);
