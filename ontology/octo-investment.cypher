// OCTO Investment Ontology — Neo4j schema of record (Cypher)
// Port of ontology/octo-investment.tql to the property-graph model (#189).
// owl:versionIRI equivalent tracked in Git tags; SemVer applies.
//
// DUAL FORMAT: every type declaration is a `// type:` comment block — the
// machine-readable schema of record the drift guard and module tests parse —
// plus the enforceable Cypher constraints that block implies. Comment syntax:
//   // type: entity | name: <kebab> | sub: <kebab-or-none> | abstract: true
//   // owns: <attr> [@key|@unique] [, <attr> ...]
//   // plays: <relation>:<role> [, ...]
//   // type: relation | name: <kebab> | reified: node   (reified when >2 roles)
//   // relates: <role> [@card(min..max)] [, ...]
//   // type: attribute | name: <kebab> | value: <type> | @values(...) | @regex(...) | @range(a..b)
//
// Mapping conventions:
//   entity          -> node label, kebab-to-Pascal (fund -> :Fund)
//   entity sub      -> multi-label chain: an :OperatingCompany also carries
//                      :Organization and :Party. Writers must set the full chain.
//   @key / @unique  -> IS UNIQUE constraint (community edition — NODE KEY and
//                      existence constraints are Enterprise-only; required-ness
//                      is enforced by the SHACL mirror, not the store).
//                      Tenant-owned entities (those that own tenant-id) are
//                      keyed per tenant: the constraint is the composite
//                      (tenantId, <key>) IS UNIQUE, so two tenants may hold the
//                      same fund or wallet. Their octo-id (the Postgres row or
//                      lineage-root id the graph projection writes from) is
//                      globally unique. Reference data (sector, country,
//                      instrument and its mints/contracts) keeps global keys.
//   relation, 2 roles   -> relationship type, kebab-to-snake
//                      (fund-sector -> :FUND_SECTOR); direction = declaration
//                      order (first role's player -> second role's player)
//   relation, >2 roles  -> reified node :Relation with one edge per role,
//                      :RELATION__ROLE e.g. (:InstrumentFlowOf)-[:FLOW_SIDE]->
//   @values/@regex/@range/@card -> carried in comments only; SHACL enforces.
// Attributes on entities/relations -> node/relationship properties, camelCase.

// ============================================================
// Attributes — identity and descriptive
// ============================================================
// type: attribute | name: legal-name | value: string
// type: attribute | name: display-name | value: string
// type: attribute | name: external-id | value: string
// type: attribute | name: tenant-id | value: string | @regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
// type: attribute | name: octo-id | value: string | @regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
// type: attribute | name: lei | value: string | @regex("^[A-Z0-9]{20}$")
// type: attribute | name: description | value: string
// type: attribute | name: email | value: string | @regex(".*@.*")
// type: attribute | name: phone | value: string
// type: attribute | name: website | value: string
// type: attribute | name: job-title | value: string
// type: attribute | name: role-title | value: string

// ============================================================
// Attributes — classification
// ============================================================
// type: attribute | name: iso-country-code | value: string | @regex("^[A-Z]{2}$")
// type: attribute | name: sector-name | value: string
// type: attribute | name: strategy-name | value: string
// type: attribute | name: currency-code | value: string | @regex("^[A-Z]{3}$")
// type: attribute | name: deal-source | value: string | @values("founder", "auction", "limited-auction", "referral", "intermediary", "proprietary", "other")
// type: attribute | name: deal-status | value: string | @values("intake", "screening", "analyst-review", "due-diligence", "ic-preparation", "ic-review", "approved", "declined", "invested", "realized")
// type: attribute | name: fund-status | value: string | @values("fundraising", "investing", "harvesting", "winding-down", "closed")
// type: attribute | name: investment-status | value: string | @values("unrealized", "partially-realized", "realized")
// type: attribute | name: document-type | value: string | @values("pitch-deck", "ddq", "financials", "icap-report", "memo", "legal", "lp-report", "tear-sheet", "other")
// type: attribute | name: flow-type | value: string | @values("contribution", "distribution", "management-fee", "expense", "carried-interest", "recallable-distribution", "other-income")
// type: attribute | name: valuation-method | value: string | @values("dcf", "comparables", "lbo", "cost", "recent-round", "mark-to-model", "other")
// type: attribute | name: screening-result | value: string | @values("pass", "conditional", "fail", "unknown", "conflicting")
// type: attribute | name: confidence-level | value: double | @range(0..1)

// ============================================================
// Attributes — onchain instruments and flows (Solana and EVM)
// ============================================================
// Base58 Solana address (no 0, O, I, l), 32-44 chars.
// type: attribute | name: solana-address | value: string | @regex("^[1-9A-HJ-NP-Za-km-z]{32,44}$")
// EVM address, lowercase 0x hex — checksummed forms are normalized at the
// adapter boundary so one address has exactly one representation.
// type: attribute | name: evm-address | value: string | @regex("^0x[0-9a-f]{40}$")
// type: attribute | name: instrument-id | value: string
// type: attribute | name: instrument-kind | value: string | @values("native-token", "spl-token", "token-2022", "nft", "stake-account", "erc20", "other")
// type: attribute | name: instrument-flow-type | value: string | @values("transfer-in", "transfer-out", "staking-reward", "airdrop", "unlock", "vesting-claim", "mint", "burn", "other")
// The chain an instrument or wallet lives on, e.g. "solana". Free string:
// the chain list is an adapter-domain concern that evolves faster than the
// ontology should.
// type: attribute | name: chain-id | value: string
// type: attribute | name: slot | value: integer
// SPL token decimals are a u8 onchain, so 0..255 — never assume ERC-20's 18.
// type: attribute | name: decimals | value: integer | @range(0..255)

// ============================================================
// Attributes — measures and timestamps
// ============================================================
// type: attribute | name: monetary-amount | value: decimal
// type: attribute | name: committed-amount | value: decimal
// type: attribute | name: invested-amount | value: decimal
// type: attribute | name: ownership-pct | value: decimal | @range(0..100)
// type: attribute | name: headcount | value: integer
// type: attribute | name: vintage-year | value: integer | @range(1900..2100)
// type: attribute | name: founded-date | value: date
// type: attribute | name: effective-date | value: date
// type: attribute | name: end-date | value: date
// type: attribute | name: occurred-at | value: datetime
// type: attribute | name: recorded-at | value: datetime
// type: attribute | name: as-of-date | value: date
// type: attribute | name: document-sha256 | value: string | @regex("^[0-9a-f]{64}$")
// type: attribute | name: file-name | value: string
// type: attribute | name: criterion-version | value: string
// type: attribute | name: model-version | value: string
// type: attribute | name: result-distribution | value: string
// type: attribute | name: rationale | value: string

// ============================================================
// Entities — parties
// ============================================================
// type: entity | name: party | abstract: true
// owns: legal-name @key, external-id, description, tenant-id, octo-id @unique
// plays: contact-for:party-side
CREATE CONSTRAINT party_tenant_legal_name_key IF NOT EXISTS
FOR (n:Party) REQUIRE (n.tenantId, n.legalName) IS UNIQUE;
CREATE CONSTRAINT party_octo_id_unique IF NOT EXISTS
FOR (n:Party) REQUIRE n.octoId IS UNIQUE;

// type: entity | name: organization | sub: party
// owns: website
// plays: employment:employer, ownership:owner, ownership:asset, co-investment:investor-side, cash-flow-attribution:counterparty, wallet-custody:owner

// type: entity | name: fund-manager | sub: organization
// plays: fund-management:manager, board-seat:organization-side

// type: entity | name: limited-partner | sub: organization
// owns: lei
// plays: commitment:investor, board-seat:organization-side

// type: entity | name: operating-company | sub: organization
// owns: founded-date
// plays: deal-subject:company-side, valuation-of:subject-side, board-seat:organization-side, co-investment:company-side, company-sector:company-side, company-domicile:company-side

// type: entity | name: data-provider | sub: organization
// plays: source-attribution:provider-side

// type: entity | name: person | sub: party
// owns: email @unique, phone, job-title
// plays: employment:employee, board-seat:member, contact-for:contact-side
CREATE CONSTRAINT person_tenant_email_unique IF NOT EXISTS
FOR (n:Person) REQUIRE (n.tenantId, n.email) IS UNIQUE;

// ============================================================
// Entities — structure and reference
// ============================================================
// type: entity | name: sector
// owns: sector-name @key
// plays: company-sector:sector-side, fund-sector:sector-side
CREATE CONSTRAINT sector_sector_name_key IF NOT EXISTS
FOR (n:Sector) REQUIRE n.sectorName IS UNIQUE;

// type: entity | name: country
// owns: iso-country-code @key
// plays: company-domicile:country-side, fund-domicile:country-side
CREATE CONSTRAINT country_iso_country_code_key IF NOT EXISTS
FOR (n:Country) REQUIRE n.isoCountryCode IS UNIQUE;

// type: entity | name: fund
// owns: legal-name @key, vintage-year, fund-status, currency-code, committed-amount, tenant-id, octo-id @unique
// plays: fund-management:vehicle, commitment:vehicle, fund-investment:vehicle, fund-sector:fund-side, fund-domicile:fund-side, cash-flow-attribution:vehicle-side
CREATE CONSTRAINT fund_tenant_legal_name_key IF NOT EXISTS
FOR (n:Fund) REQUIRE (n.tenantId, n.legalName) IS UNIQUE;
CREATE CONSTRAINT fund_octo_id_unique IF NOT EXISTS
FOR (n:Fund) REQUIRE n.octoId IS UNIQUE;

// type: entity | name: deal
// owns: display-name @key, deal-source, deal-status, effective-date, tenant-id, octo-id @unique
// plays: deal-subject:deal-side, fund-investment:deal-side, document-about:deal-side, screening-of:deal-side
CREATE CONSTRAINT deal_tenant_display_name_key IF NOT EXISTS
FOR (n:Deal) REQUIRE (n.tenantId, n.displayName) IS UNIQUE;
CREATE CONSTRAINT deal_octo_id_unique IF NOT EXISTS
FOR (n:Deal) REQUIRE n.octoId IS UNIQUE;

// type: entity | name: investment
// owns: display-name @key, investment-status, tenant-id, octo-id @unique
// plays: fund-investment:position, valuation-of:subject-side, cash-flow-attribution:position-side
CREATE CONSTRAINT investment_tenant_display_name_key IF NOT EXISTS
FOR (n:Investment) REQUIRE (n.tenantId, n.displayName) IS UNIQUE;
CREATE CONSTRAINT investment_octo_id_unique IF NOT EXISTS
FOR (n:Investment) REQUIRE n.octoId IS UNIQUE;

// ============================================================
// Entities — ledger events and documents
// ============================================================
// Append-only financial events. Instances are never mutated; corrections are
// new events referencing the original via supersedes.
// type: entity | name: ledger-event
// owns: flow-type, monetary-amount, currency-code, occurred-at, recorded-at, external-id
// plays: cash-flow-attribution:event-side, supersedes:replacement, supersedes:original, source-attribution:data-side

// type: entity | name: valuation-event
// owns: monetary-amount, external-id, currency-code, as-of-date, valuation-method
// plays: valuation-of:valuation-side, supersedes:replacement, supersedes:original, source-attribution:data-side

// type: entity | name: document
// owns: file-name @key, document-type, document-sha256, recorded-at, tenant-id, octo-id @unique
// plays: document-about:document-side, extraction-source:document-side, source-attribution:data-side
CREATE CONSTRAINT document_tenant_file_name_key IF NOT EXISTS
FOR (n:Document) REQUIRE (n.tenantId, n.fileName) IS UNIQUE;
CREATE CONSTRAINT document_octo_id_unique IF NOT EXISTS
FOR (n:Document) REQUIRE n.octoId IS UNIQUE;

// type: entity | name: extracted-claim
// owns: description, confidence-level, model-version, external-id, recorded-at
// plays: extraction-source:claim-side

// type: entity | name: screening-decision
// owns: screening-result, criterion-version, model-version, result-distribution, confidence-level, external-id, rationale, recorded-at
// plays: screening-of:decision-side

// ============================================================
// Entities — onchain instruments and flows
// ============================================================
// Anything a wallet can hold that is not an ISO currency. instrument-id is the
// canonical key ("solana:native", "solana:mint:<address>",
// "arbitrum-one:contract:<address>"); mints, contracts, and symbols are
// metadata, never keys.
// type: entity | name: instrument
// owns: instrument-id @key, instrument-kind, chain-id, decimals
// plays: instrument-flow-of:instrument-side
CREATE CONSTRAINT instrument_instrument_id_key IF NOT EXISTS
FOR (n:Instrument) REQUIRE n.instrumentId IS UNIQUE;

// type: entity | name: solana-mint | sub: instrument
// owns: solana-address @unique
CREATE CONSTRAINT solana_mint_solana_address_unique IF NOT EXISTS
FOR (n:SolanaMint) REQUIRE n.solanaAddress IS UNIQUE;

// An ERC-20/EVM contract-as-instrument — the evm-mint parallel to solana-mint.
// type: entity | name: evm-contract | sub: instrument
// owns: evm-address @unique
CREATE CONSTRAINT evm_contract_evm_address_unique IF NOT EXISTS
FOR (n:EvmContract) REQUIRE n.evmAddress IS UNIQUE;

// type: entity | name: wallet
// owns: solana-address @key, chain-id, tenant-id, octo-id @unique
// plays: instrument-flow-of:wallet-side, wallet-custody:wallet-side
CREATE CONSTRAINT wallet_tenant_solana_address_key IF NOT EXISTS
FOR (n:Wallet) REQUIRE (n.tenantId, n.solanaAddress) IS UNIQUE;
CREATE CONSTRAINT wallet_octo_id_unique IF NOT EXISTS
FOR (n:Wallet) REQUIRE n.octoId IS UNIQUE;

// The EVM wallet is a separate entity rather than a widened `wallet`: rekeying
// `wallet` would be a MAJOR change, and a parallel entity is additive
// (arbitrum-ingestion-design.md §Ontology). A multi-chain wallet supertype is
// deferred to the second EVM chain, when the shared shape is known.
// type: entity | name: evm-wallet
// owns: evm-address @key, chain-id, tenant-id, octo-id @unique
// plays: instrument-flow-of:wallet-side, wallet-custody:wallet-side
CREATE CONSTRAINT evm_wallet_tenant_evm_address_key IF NOT EXISTS
FOR (n:EvmWallet) REQUIRE (n.tenantId, n.evmAddress) IS UNIQUE;
CREATE CONSTRAINT evm_wallet_octo_id_unique IF NOT EXISTS
FOR (n:EvmWallet) REQUIRE n.octoId IS UNIQUE;

// Append-only token-denominated flows. Fiat flows stay on ledger-event; token
// positions are derived from these, never stored.
// type: entity | name: instrument-flow
// owns: instrument-flow-type, monetary-amount, occurred-at, recorded-at, external-id, slot
// plays: instrument-flow-of:flow-side, supersedes:replacement, supersedes:original, source-attribution:data-side

// ============================================================
// Relations — organizational
// ============================================================
// type: relation | name: employment
// relates: employee @card(1), employer @card(1)
// owns: role-title, effective-date, end-date

// type: relation | name: board-seat
// relates: member @card(1), organization-side @card(1)
// owns: role-title, effective-date, end-date

// type: relation | name: contact-for
// relates: contact-side @card(1), party-side @card(1)
// owns: effective-date

// type: relation | name: co-investment
// relates: investor-side @card(1..), company-side @card(1)
// owns: effective-date

// ============================================================
// Relations — fund and deal structure
// ============================================================
// type: relation | name: fund-management
// relates: manager @card(1), vehicle @card(1)
// owns: effective-date

// A commitment is also a valuation subject: the LP capital-account NAV (issue #21).
// type: relation | name: commitment
// relates: investor @card(1), vehicle @card(1)
// owns: committed-amount, currency-code, effective-date
// plays: valuation-of:subject-side

// type: relation | name: deal-subject
// relates: deal-side @card(1), company-side @card(1)

// type: relation | name: fund-investment | reified: node
// relates: vehicle @card(1), deal-side @card(1), position @card(1)
// owns: effective-date

// type: relation | name: company-sector
// relates: company-side @card(1), sector-side @card(1)

// type: relation | name: fund-sector
// relates: fund-side @card(1), sector-side @card(1)

// type: relation | name: company-domicile
// relates: company-side @card(1), country-side @card(1)

// type: relation | name: fund-domicile
// relates: fund-side @card(1), country-side @card(1)

// ============================================================
// Relations — look-through ownership
// ============================================================
// Recursive ownership edges power look-through exposure aggregation. Both
// roles are organization-scoped; a fund -> company look-through edge is not
// expressible today.
// TODO(issue-id): decide whether ownership should be party-scoped, or whether
// fund/operating-company should play both roles. The OWL/SHACL mirror follows
// the declaration as written.
// type: relation | name: ownership
// relates: owner @card(1), asset @card(1)
// owns: ownership-pct, effective-date, end-date

// ============================================================
// Relations — ledger and valuation attribution
// ============================================================
// type: relation | name: cash-flow-attribution | reified: node
// relates: event-side @card(1), position-side @card(0..1), counterparty @card(0..1), vehicle-side @card(0..1)

// type: relation | name: valuation-of
// relates: valuation-side @card(1), subject-side @card(1)

// Corrections are new events superseding prior ones; history is preserved.
// type: relation | name: supersedes
// relates: replacement @card(1), original @card(1)
// owns: rationale, recorded-at

// ============================================================
// Relations — documents, extraction, screening
// ============================================================
// type: relation | name: document-about
// relates: document-side @card(1), deal-side @card(1)

// type: relation | name: extraction-source
// relates: claim-side @card(1), document-side @card(1)
// owns: confidence-level

// type: relation | name: source-attribution
// relates: provider-side @card(1), data-side @card(1)
// owns: recorded-at

// type: relation | name: screening-of
// relates: decision-side @card(1), deal-side @card(1)

// ============================================================
// Relations — onchain
// ============================================================
// type: relation | name: instrument-flow-of | reified: node
// relates: flow-side @card(1), instrument-side @card(1), wallet-side @card(1)

// Which organization controls a wallet (treasury, custody, fund wallet).
// type: relation | name: wallet-custody
// relates: wallet-side @card(1), owner @card(1)
// owns: effective-date, end-date
