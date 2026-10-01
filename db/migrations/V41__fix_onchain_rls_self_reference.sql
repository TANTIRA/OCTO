-- V41__fix_onchain_rls_self_reference.sql
-- Qualify the outer-row columns in the tracked_address-derived tenant policies (#481).
--
-- Grounded in:
--   V27__tenant_row_level_security.sql      tracked_address_event policy
--   V30__pre_v9_entity_tenant_scoping.sql   onchain_transfer, onchain_balance_snapshot,
--                                           instrument_flow, onchain_claim_evidence policies
--   CLAUDE.md                               applied Flyway files are immutable — fixed forward here
--
-- Postgres resolves an unqualified column to the innermost FROM that has it. In
--   (select ta.tenant_id from octo.tracked_address ta where ta.chain = chain ...)
-- `chain` (and in V27 `address` as well) bound to ta, not to the row under check, and that
-- binding is fixed in the catalog at create time:
--   * tracked_address_event: both columns self-compare, so the subquery returns every tracked
--     address visible in scope — "more than one row returned by a subquery" once two are visible,
--     and the wrong tenant for every event when exactly one is.
--   * the four V30 tables: wallet/subject_address bound correctly but `chain` self-compared, so
--     the same EVM address on two chains (V17) errors or resolves to the other chain's tenant.
-- Each policy below is the original verbatim except that the outer column is table-qualified;
-- name, command (all), roles (public), USING and WITH CHECK are unchanged.

drop policy if exists tenant_scope on octo.tracked_address_event;
create policy tenant_scope on octo.tracked_address_event
    using (octo.rls_admits((select ta.tenant_id from octo.tracked_address ta
                            where ta.chain = tracked_address_event.chain
                              and ta.address = tracked_address_event.address)))
    with check (octo.rls_admits((select ta.tenant_id from octo.tracked_address ta
                                 where ta.chain = tracked_address_event.chain
                                   and ta.address = tracked_address_event.address)));

drop policy if exists tenant_scope on octo.onchain_transfer;
create policy tenant_scope on octo.onchain_transfer
    using (octo.rls_admits((select ta.tenant_id from octo.tracked_address ta
                            where ta.chain = onchain_transfer.chain
                              and ta.address = onchain_transfer.wallet)))
    with check (octo.rls_admits((select ta.tenant_id from octo.tracked_address ta
                                 where ta.chain = onchain_transfer.chain
                                   and ta.address = onchain_transfer.wallet)));

drop policy if exists tenant_scope on octo.onchain_balance_snapshot;
create policy tenant_scope on octo.onchain_balance_snapshot
    using (octo.rls_admits((select ta.tenant_id from octo.tracked_address ta
                            where ta.chain = onchain_balance_snapshot.chain
                              and ta.address = onchain_balance_snapshot.wallet)))
    with check (octo.rls_admits((select ta.tenant_id from octo.tracked_address ta
                                 where ta.chain = onchain_balance_snapshot.chain
                                   and ta.address = onchain_balance_snapshot.wallet)));

drop policy if exists tenant_scope on octo.instrument_flow;
create policy tenant_scope on octo.instrument_flow
    using (octo.rls_admits((select ta.tenant_id from octo.tracked_address ta
                            where ta.chain = instrument_flow.chain
                              and ta.address = instrument_flow.wallet)))
    with check (octo.rls_admits((select ta.tenant_id from octo.tracked_address ta
                                 where ta.chain = instrument_flow.chain
                                   and ta.address = instrument_flow.wallet)));

drop policy if exists tenant_scope on octo.onchain_claim_evidence;
create policy tenant_scope on octo.onchain_claim_evidence
    using (octo.rls_admits((select ta.tenant_id from octo.tracked_address ta
                            where ta.chain = onchain_claim_evidence.chain
                              and ta.address = onchain_claim_evidence.subject_address)))
    with check (octo.rls_admits((select ta.tenant_id from octo.tracked_address ta
                                 where ta.chain = onchain_claim_evidence.chain
                                   and ta.address = onchain_claim_evidence.subject_address)));
