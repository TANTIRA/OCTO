-- V38__fk_covering_indexes.sql
-- Supabase lint 0001 (unindexed_foreign_keys): thirteen foreign keys have no index whose
-- leftmost columns cover them. Three classes of cost:
--   * tenant_id FKs on RLS tables — every tenant-scoped query filters on tenant_id, and the
--     child check on a tenant row's update/delete locks and scans the whole child table;
--   * child-side FKs (asset_xref.asset_id, reconciliation_break.*, compliance_evaluation.task_id,
--     report_job.approval_task_id) — reverse lookups and join paths;
--   * supersedes_id chains — supersede traversal and parent delete checks.
-- Names follow the existing <table>_<column>_idx convention.

create index asset_tenant_id_idx on octo.asset (tenant_id);
create index asset_supersedes_id_idx on octo.asset (supersedes_id);
create index asset_xref_asset_id_idx on octo.asset_xref (asset_id);
create index compliance_evaluation_task_id_idx on octo.compliance_evaluation (task_id);
create index model_run_supersedes_id_idx on octo.model_run (supersedes_id);
create index onchain_balance_snapshot_supersedes_id_idx on octo.onchain_balance_snapshot (supersedes_id);
create index onchain_claim_evidence_supersedes_id_idx on octo.onchain_claim_evidence (supersedes_id);
create index reconciliation_break_ledger_event_id_idx on octo.reconciliation_break (ledger_event_id);
create index reconciliation_break_task_id_idx on octo.reconciliation_break (task_id);
create index report_job_approval_task_id_idx on octo.report_job (approval_task_id);
create index report_job_tenant_id_idx on octo.report_job (tenant_id);
create index timeseries_observation_supersedes_id_idx on octo.timeseries_observation (supersedes_id);
create index tracked_address_tenant_id_idx on octo.tracked_address (tenant_id);
