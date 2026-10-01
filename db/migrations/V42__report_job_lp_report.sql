-- V42__report_job_lp_report.sql
-- Adds the 'lp-report' report type (#279 F8, agent-drafted LP letter) to report_job (#488).
--
-- Grounded in:
--   #279                           ReportType.LP_REPORT rides the report_job pipeline, but no migration
--                                  widened the type check, so every POST /api/v1/reports with type
--                                  lp-report failed at insert
--   V29                            the previous widening of report_job_type_known (gl-export)
--
-- On-demand only, like gl-export: the drafter narrates the job's inline parameters, so a fixed schedule
-- template would re-narrate the same figures; report_schedule_type_known is left as-is and
-- ReportScheduleController rejects non-schedulable types with 400. Numbered after V41 (#481); Flyway
-- rejects out-of-order versions, so this must merge after it. ALTER of a constraint is DDL, so the
-- append-only reject_mutation trigger does not block it.

alter table octo.report_job
    drop constraint report_job_type_known,
    add constraint report_job_type_known
        check (report_type in ('performance', 'exposure', 'attribution', 'gl-export', 'lp-report'));
