-- V28__report_job_gl_export.sql
-- Adds the 'gl-export' report type (#6 slice 15, accounting/GL export feed) to report_job.
--
-- Grounded in:
--   #6 slice 15                     an outbound double-entry journal derived from the ledger
--   docs/system-design.md          outbound artifacts run as report jobs and pass the approval gate
--   V13                            report_type is guarded by a check constraint, not an enum type
--
-- GL export reuses the whole report_job pipeline (queue, in-process runner, artifact, release gate),
-- so the only schema change is widening the type check. On-demand only: the report_schedule type
-- check (V22) is left as-is because a fixed cron template cannot carry the changing event set a
-- journal needs. ALTER of a constraint is DDL, so the append-only reject_mutation trigger (which
-- fires on row mutation) does not block it.

alter table mesta.report_job
    drop constraint report_job_type_known,
    add constraint report_job_type_known
        check (report_type in ('performance', 'exposure', 'attribution', 'gl-export'));
