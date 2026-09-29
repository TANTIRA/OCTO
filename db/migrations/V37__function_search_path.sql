-- V37__function_search_path.sql
-- Supabase lint 0011 (function_search_path_mutable): the five trigger functions created
-- before the V36 convention carry no SET search_path, so their bodies resolve names through
-- the calling role's search_path — a role with CREATE on a schema earlier in the path could
-- shadow a referenced object. Pin each to the V36 convention (pg_catalog first, then pg_temp);
-- every body already schema-qualifies its octo.* references, so resolution is unchanged.

alter function octo.ledger_event_reject_mutation() set search_path = pg_catalog, pg_temp;
alter function octo.reject_mutation() set search_path = pg_catalog, pg_temp;
alter function octo.workflow_task_event_segregation() set search_path = pg_catalog, pg_temp;
alter function octo.tenant_member_event_rules() set search_path = pg_catalog, pg_temp;
alter function octo.tracked_address_event_rules() set search_path = pg_catalog, pg_temp;
