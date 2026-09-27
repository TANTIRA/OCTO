<!-- TANTIRA repo template. Source of truth: repo TANTIRA. Fill every [ ] placeholder. Do not edit the "Company rules" sections — they are identical in every repo. -->

# AGENTS.md — OCTO

Private-equity investment platform: a standardized Ontology (prospects, funds, portfolio companies, LPs, GPs) on a single IBOR, consolidating CRMs, financial and market-data providers, documents, and third-party/open-source feeds — with configurable deal screening, AI-assisted due diligence and IC reporting, performance analytics, low-code models, alerts, NL queries, LP reports, and governed workflows. One database, one system, one process.

- **Product line:** Operations
- **Owners (CODEOWNERS team):** @TANTIRA/octo
- **Default risk tier:** T1 — anything writing financial data (IBOR, reconciliation, migrations, auth, vendor ingestion) is T2. See Risk tiers below.
- **Runtime:** Kotlin on Java 21, Spring Boot 3, Gradle (Kotlin DSL), self-hosted Supabase PostgreSQL + Flyway

## Rules

- write production code, bridge technology with business goals.
- No Slop, No Stub, No Mock, No False Positive Code. Every Code must be deliver the production quality.
- always cross-check the final works before commiting and push to repository, prevent from conflict when it takes to creating pull request.
- code must have this points: simple, usable, scalable, and secure/safe.
- When the task is complex, delegates sub agents to solving the problems.