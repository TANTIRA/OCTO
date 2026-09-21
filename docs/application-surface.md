# Application Surface — Reference Mapping

How the ontology-aware application model maps onto Mesta-Asset. The reference platform ships general-purpose object-aware applications (views, explorer, analysis canvas, app builders, workspaces, map). Mesta-Asset is a **productized** surface: the same concepts are delivered as fixed, purpose-built screens plus a small set of governed configuration surfaces — not a general app-builder ecosystem.

## Concept mapping

| Reference application | Mesta-Asset equivalent | Status |
| --- | --- | --- |
| Object Views — 360° hub per object | Dossier tabs: Company Details, Fund Metrics, prospect detail — biography data, linked objects, metrics, related workflow | Adopted |
| Object Explorer — visual search, search-around, object sets, bulk actions | Global query bar + NL query; entity drill-down; Investment Metrics table as the object-set surface; bulk actions on selected rows | Adopted (narrower) |
| Quiver — point-and-click analysis, aggregations, time series, publishable dashboards | `analytics` module: metric authoring DSL + chart panels; published read-only views = dashboard mode | Adopted (engine, not canvas) |
| Workshop — code-less workflow apps on objects | Fixed workflow screens (deal pipeline stages, recon queue, approval queue) configured by rules/templates, not free-form app building | Adopted (constrained) |
| Slate — code-level app building | N/A — custom surfaces are real code in this repo, not an embedded builder | Out of scope |
| Carbon — curated workspaces combining apps | Persona landing surfaces: Control Panel inbox as operational workspace; role-scoped default tabs | Adopted (implicit) |
| Map — geospatial analysis | Country/domicile fields exist in Ontology; map visualization deferred until geographic analysis demand is proven | Deferred |

## Application inventory vs. comparison dimensions

Using the reference dimensions — primary use case, workflow style, configuration model:

| Surface | Primary use case | Workflow style | Configuration model |
| --- | --- | --- | --- |
| Deal Pipeline | Application | Workflow-specific | Walk-up usable; criteria/templates are governed config |
| Portfolio Overview | Dashboard | Workflow-specific | Walk-up usable |
| Fund Metrics | Dashboard + Analysis | Workflow-specific | Walk-up usable |
| Investment Metrics | Discovery + Analysis | Exploratory (filters) | Walk-up usable |
| Company Details | Discovery | Workflow-specific | Walk-up usable |
| Control Panel | Application (operational inbox) | Workflow-specific | Walk-up usable; AI rules are governed config |
| NL query | Discovery + Analysis | Exploratory | Walk-up usable |
| Metric authoring | Analysis (builder) | Workflow-specific for consumers | Customizable — builder produces, users consume |
| Report templates | Application (builder) | Workflow-specific | Customizable — governed, versioned |
| Screening criteria | Application (builder) | Workflow-specific | Customizable — strategy-scoped, approved |
| Recon queue | Application | Workflow-specific | Walk-up usable |
| Approval queue | Application | Workflow-specific | Walk-up usable |
| Source admin | Application (builder) | Workflow-specific | Customizable — adapter mappings |

## Principles adopted from the model

1. **Walk-up usable beats customizable.** Default surfaces work immediately once the Ontology is populated. Builder surfaces exist only where firm-specific variation is real: screening criteria, DDQ libraries, report templates, metrics, alert rules.
2. **Builder/consumer split is explicit.** Every customizable surface has a governed builder path (versioned config, test/backtest, approval) and a simple consumer experience. Nobody edits a production screen.
3. **Exploratory paths resolve into governed artifacts.** An NL query or filtered object set can be saved as a view, promoted to a dashboard panel, or cited in a report — but promotion goes through versioning and review.
4. **Every surface is permission-scoped.** Exploration and NL query return only entities the user can see; object sets never leak restricted entities as locked rows.
5. **Writeback is an action type.** Bulk actions on object sets (e.g. update status, assign owner, export) go through governed commands — permissioned, audited, approval-gated where material.

## Deliberately not adopted

- **Free-form app builder (Slate-like)** — internal tooling needs are served by configured templates + code, not an embedded platform.
- **Map surface** — deferred; domicile/country fields support it later without schema change.
- **Per-object configurable views** — dossiers are standardized; consistency is the product's pitch.

## Where each concept lives

| Layer | Implementation |
| --- | --- |
| Object views | `Company Details`, `Fund Metrics`, prospect detail |
| Search/exploration | Global query bar, Investment Metrics filters, NL query |
| Analysis engine | `modules/analytics` — metric DSL, aggregations, time series |
| Workflow apps | `modules/workflow` + fixed screens (pipeline, recon, approvals) |
| Workspace | `modules/control-panel` — inbox, alerts, drafts, news |
| Builder surfaces | Screening criteria, DDQ library, report templates, metric authoring, rule authoring |
