# Mesta-Asset

Private-equity portfolio management platform. One database, one system, one process.

## Overview

Mesta-Asset is a private-equity investment and portfolio management platform that consolidates fragmented data, systems, and operating processes into a cohesive Investment Ontology and a single IBOR (Investment Book of Record). It normalizes inconsistent data from CRMs, financial and market-data providers, fund administrators, internal systems, documents, and third-party or open-source feeds into a governed source of truth spanning prospects, funds, portfolio companies, LPs, and GPs.

The platform provides a configurable front end for deal sourcing, portfolio monitoring, analytics, reporting, and operational oversight. Firms can tailor workflows, permissions, metrics, models, and output artifacts to their business model while preserving a standardized organization-wide methodology. Outputs include tear sheets, portfolio-rebalancing analyses, valuation models, Investment Committee reports, disclosure materials, and reports for Limited Partners. Governed data can also be accessed through spreadsheets and external systems.

Mesta-Asset addresses the absence of a centralized view, duplicated processes across teams and locations, overlapping tools, inconsistent analyst methodologies, and discrepancies between vendor datasets. The result is simplicity, control, efficiency, and scalability.

**Vendor-neutral by design.** Mesta-Asset does not replace fund administrators, custodians, CRMs, or data providers. It operates above them in a multi-vendor environment and isolates provider-specific formats within ingestion adapters. Consolidation occurs at the data and process layer, so firms can add or replace providers without changing the core platform.

## Capabilities

| Capability | Description |
| --- | --- |
| IBOR | Append-only transaction/cash-flow ledger; positions, commitments, and drawdowns are derived — never written |
| Investment Ontology | Dynamic OWL/SHACL canonical model in Git, versioned with SemVer; supports monitoring, analytics, reporting, and governed access from the platform, spreadsheets, and external systems |
| AI data integration | Vendor-isolated adapters for CRMs, financial and market-data providers, third-party and open-source feeds; multimodal extraction from pitch decks, PDFs, and other documents; built-in validation, lineage, and governance |
| Deal sourcing | Configurable inbound screening, standardized prospect pipeline, AI-assisted DDQs, and source-grounded Investment Committee reports with human review |
| Reconciliation | Continuous validation of source systems against the IBOR; discrepancies surface as workflow tasks |
| Look-through | Recursive exposure aggregation across fund → deal → portfolio company hierarchies |
| Modelling and analytics | Metrics engine (IRR, TVPI, MOIC, DPI) and low-code authoring for firm-specific metrics, ratios, forecasts, and valuation models |
| Personalized outputs | Configurable tear sheets, portfolio-rebalancing analyses, IC memos, disclosure materials, and LP reports |
| AI-driven applications | Proactive reporting, alert rules, natural-language data queries, memo/email drafting, news matching, and bespoke GP/LP interfaces |
| Permissions and collaboration | Fine-grained RBAC/ABAC controls for cross-functional teams, external users, entities, documents, fields, actions, and outputs |
| Security and governance | Data classification, encryption, lineage, retention, segregation of duties, tamper-evident audit, model governance, and controlled third-party processing |
| Self-hosted deployment | Supabase PostgreSQL, Auth, and Storage run on controlled infrastructure; the operator owns hardening, availability, monitoring, backups, PITR, upgrades, and disaster recovery |
| Workflow | Task assignment, approvals, and disclosure management; human approval gates outbound artifacts |

## AI-assisted deal sourcing

Mesta-Asset accelerates early-stage deal sourcing by enabling private-equity analyst and research teams to analyze inbound opportunities and manage prospects at scale. Firms can configure strategies, criteria, question libraries, report templates, and stage-transition rules while retaining a unified organization-wide methodology. This combines local flexibility with consistent analysis, evidence requirements, review controls, and decision records across teams.

Multimodal models extract text, tables, chart values, entities, and claims from pitch decks, PDFs, and other documents. The platform maps these results into the Investment Ontology and combines them with structured internal and third-party data. Every extracted claim retains a citation to its source location, confidence, model version, and review status.

### Screening

Users define versioned screening criteria by strategy, such as minimum revenue, five-year growth, permitted countries, sector restrictions, or document recency. The platform evaluates inbound opportunities as `Pass`, `Conditional`, `Fail`, `Unknown`, or `Conflicting` and recommends a pipeline transition. Analysts review results, request missing evidence, and may override recommendations with a recorded rationale. The platform never silently converts missing information into a failed criterion.

### Due diligence

The platform suggests answers to approved Due Diligence Questionnaire libraries covering company vision, differentiation, market position, financial quality, management, principal risks, and mitigants. It also proposes company-specific questions based on the submitted materials, inconsistencies, evidence gaps, and existing answers. Suggested answers include source citations and remain unverified until an analyst reviews them. Users can edit responses, export unanswered questions, and upload follow-up materials for extraction and reassessment.

### Investment Committee reports

Mesta-Asset generates reports from approved, versioned templates populated with governed Ontology data. Structured grounding and answer-level citations reduce unsupported model output. Reports can include the investment thesis, market analysis, financials, valuation and return scenarios, diligence findings, risks, mitigants, and open conditions. Users review and manually edit every report before submitting it for approval, export, or email distribution.

See [Deal sourcing workflow](docs/deal-sourcing-workflow.md) for stages, controls, and the prospect-to-investment conversion flow.

## Architecture

Modular monolith: Kotlin on Java 21, Spring Boot 3, Gradle, Flyway, and self-hosted Supabase PostgreSQL/Auth/Storage. Spring Boot remains the domain and API boundary; sensitive domain writes are never exposed directly to clients. See [ADR-0001](docs/adr/0001-platform-architecture.md) for the platform architecture and [ADR-0002](docs/adr/0002-self-hosted-supabase.md) for deployment, security, backup, and operational responsibilities.

## Product documentation

- [Platform architecture and end-to-end flow](docs/adr/0001-platform-architecture.md)
- [Self-hosted Supabase architecture and operations](docs/adr/0002-self-hosted-supabase.md)
- [TypeDB ontology store](docs/adr/0003-typedb-ontology-store.md) — schema: [`ontology/mesta-investment.tql`](ontology/mesta-investment.tql)
- [Ontology concepts — reference mapping](docs/ontology-concepts.md)
- [Ontology design guidelines](docs/ontology-design-guidelines.md)
- [AI architecture — platform capability mapping](docs/ai-architecture.md)
- [Application surface — ontology-aware app model](docs/application-surface.md)
- [Deal sourcing, screening, due diligence, and IC workflow](docs/deal-sourcing-workflow.md)
- [User workflows and application flow](docs/user-workflows.md)
- [User interfaces](docs/user-interfaces.md)
- [User journey](docs/user-journey.md)
- [User experience principles](docs/user-experience.md)
- [Quantitative methodology](docs/quantitative-methodology.md)
- [Data security and governance](docs/data-security-governance.md)

## Governance

This repo follows the TANTIRA SDLC — see [AGENTS.md](AGENTS.md). Highlights: work starts from a GitHub Issue, conventional commits, PRs under 400 lines, risk tiers T0–T3, no commits to `main`, no deploys by agents.

## Repository layout

```text
modules/
  api/            Spring Boot entry point — REST boundary, Flyway wiring, actuator
  ibor-core/      Append-only ledger: commitments, transactions, cash flows, valuations
  lookthrough/    Recursive exposure aggregation across fund → deal → company
  ingestion/      Source adapters and document staging (vendor isolation boundary)
  recon/          Source-vs-IBOR comparison, discrepancy surfacing
  analytics/      Metrics engine: IRR, TVPI, MOIC, DPI; low-code metric authoring
  deal-sourcing/  Screening, DDQ assistance, IC report assembly
  workflow/       Tasks, approvals, outbound-artifact gates
  control-panel/  Unified inbox: alerts, recon items, approvals, AI proposals
db/migrations/    Flyway migrations (append-only, migration identity)
ontology/         TypeDB schema — mesta-investment.tql (T2, SemVer)
infra/            Dokploy Compose definitions and Supabase override
docs/             Architecture, ADRs, product and methodology documentation
```

The web UI is a separate frontend concern (ADR-0001); `infra/docker-compose.yml` consumes it as a published `mesta-web` image.

## Status

Backend scaffold in place — `./gradlew check` compiles all modules and runs tests. Domain logic not yet implemented; see `docs/adr/` for agreed architecture decisions.
