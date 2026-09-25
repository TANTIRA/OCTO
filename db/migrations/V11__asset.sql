-- V11__asset.sql
-- Asset master over the ontology's asset-holding types, with an external-identifier table (issue #103,
-- #6 slice 5, Marquee asset service).
--
-- Grounded in:
--   #6 decision 3 (2026-09-24)   asset types come from the ontology (fund, investment, operating-company),
--                                not Marquee's open list; listed instruments wait for the CTO (#109)
--   #6 decision 2 (2026-09-25)   every new domain table carries tenant_id from its first migration
--   ADR-0003                     every PostgreSQL record that produces graph state records the TypeDB IID
--   ontology/mesta-investment.tql lei @regex("^[A-Z0-9]{20}$"), iso-country-code @regex("^[A-Z]{2}$")
--
-- Append-only like valuation_event: a change to an asset is a new row that supersedes the old one with a
-- rationale. An external identifier belongs to the asset's lineage, so a superseding row inherits the
-- identifiers of the rows it replaces and unique (scheme, value) holds across the whole table.
-- Numbered V11 because V10 is reserved by #110 (onchain ingestion); must merge after it or be renumbered.

create table mesta.asset (
    id             uuid        primary key default gen_random_uuid(),
    tenant_id      uuid        not null references mesta.tenant (id),
    asset_type     text        not null,
    asset_class    text        not null,
    display_name   text        not null,
    region         char(2),
    tags           text[]      not null default '{}',
    typedb_iid     text,
    supersedes_id  uuid        references mesta.asset (id),
    rationale      text,
    source_system  text        not null,
    actor          text        not null,
    correlation_id uuid        not null,
    recorded_at    timestamptz not null default now(),

    constraint asset_type_known check (asset_type in ('fund', 'investment', 'operating-company')),
    constraint asset_named check (
        length(btrim(asset_class)) > 0 and length(btrim(display_name)) > 0
        and length(btrim(source_system)) > 0 and length(btrim(actor)) > 0),
    constraint asset_region_shape check (region is null or region ~ '^[A-Z]{2}$'),
    constraint asset_tags_no_empty check ('' <> all(tags) and array_position(tags, null) is null),
    constraint asset_no_self_supersede check (supersedes_id is null or supersedes_id <> id),
    constraint asset_rationale_required_when_superseding
        check (supersedes_id is null or length(btrim(coalesce(rationale, ''))) > 0)
);

comment on table mesta.asset is
    'Append-only asset master over the ontology types fund, investment and operating-company. A change supersedes with a rationale.';

create table mesta.asset_xref (
    id          uuid        primary key default gen_random_uuid(),
    asset_id    uuid        not null references mesta.asset (id),
    scheme      text        not null,
    value       text        not null,
    recorded_at timestamptz not null default now(),

    -- A scheme is a lower-case token, optionally namespaced: lei, isin, crm, vendor:preqin.
    constraint asset_xref_scheme_shape check (scheme ~ '^[a-z][a-z0-9-]*(:[a-z0-9-]+)?$'),
    constraint asset_xref_value_named check (length(btrim(value)) > 0),
    constraint asset_xref_lei_shape check (scheme <> 'lei' or value ~ '^[A-Z0-9]{20}$'),
    -- One identifier names one asset lineage.
    constraint asset_xref_unique unique (scheme, value)
);

comment on table mesta.asset_xref is
    'External identifiers of an asset lineage: lei, isin, CRM and vendor ids. unique (scheme, value) across the table.';

create trigger asset_append_only
    before update or delete on mesta.asset
    for each row
    execute function mesta.reject_mutation();

create trigger asset_no_truncate
    before truncate on mesta.asset
    for each statement
    execute function mesta.reject_mutation();

create trigger asset_xref_append_only
    before update or delete on mesta.asset_xref
    for each row
    execute function mesta.reject_mutation();

create trigger asset_xref_no_truncate
    before truncate on mesta.asset_xref
    for each statement
    execute function mesta.reject_mutation();

grant select, insert on mesta.asset, mesta.asset_xref to "${runtime_role}";
