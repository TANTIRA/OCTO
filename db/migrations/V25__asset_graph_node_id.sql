-- V25__asset_graph_node_id.sql
-- #189: the graph store is Neo4j (ADR-0004 supersedes ADR-0003), so the V11 `typedb_iid`
-- column takes a store-agnostic name before any writer lands values under it. The rename
-- keeps the column's data and leaves the append-only triggers untouched — they fire on row
-- mutation, not on DDL.
--
-- Safe to rename: the only readers/writers are JdbcAssetStore.create/load, and nothing
-- populates the field yet — Asset exposes it but no production caller sets it.

alter table mesta.asset rename column typedb_iid to graph_node_id;

comment on column mesta.asset.graph_node_id is
    'Element id of the node this row produced in the graph store — the Neo4j elementId today (ADR-0004); it was the TypeDB IID before the swap.';
