-- =============================================================================
-- V16 — onchain claim evidence (issue #116, megaplan phase 8)
--
-- Wedge tie-in: a token-project deck claims onchain facts ("10k holders", "$2M
-- treasury", "mainnet live"). The evidence adapter resolves the claim's subject
-- address and writes what the chain actually reports — never the verdict. The
-- verdict is a separate decision point that consumes these rows.
--
-- Grounding, same as V10:
--   V2__decision_staging.sql  append-only staging boilerplate + unique
--                             (source_system, external_id)
--   V10__onchain_ingestion.sql  the onchain staging family this table joins
--
-- external_id is the *query's* identity: "<chain>:<kind>:<subject>[:<scope>]".
-- Re-running the same check produces the same row and dedupes on the unique key;
-- a later observation of the same subject is a new fact (different as_of in the
-- key scope or a supersession chain, never an UPDATE).
-- =============================================================================

create table mesta.onchain_claim_evidence (
    id                 uuid            primary key default gen_random_uuid(),
    external_id        text            not null,
    claim_ref          text            not null,
    chain              text            not null,
    subject_address    text            not null,
    evidence_kind      text            not null,
    observed_numeric   numeric(38, 10),
    observed_text      text,
    observed_payload   jsonb           not null,
    as_of              timestamptz     not null,
    recorded_at        timestamptz     not null default now(),
    supersedes_id      uuid            references mesta.onchain_claim_evidence (id),
    rationale          text,
    source_system      text            not null,
    actor              text            not null,
    ingestion_run_id   uuid            not null,
    correlation_id     uuid            not null,

    constraint onchain_claim_evidence_kind_known check (evidence_kind in (
        'token-supply',
        'holder-concentration',
        'treasury-balance',
        'account-activity',
        'other'
    )),
    constraint onchain_claim_evidence_address_shape
        check (subject_address ~ '^[1-9A-HJ-NP-Za-km-z]{32,44}$'),
    constraint onchain_claim_evidence_some_observation
        check (observed_numeric is not null or observed_text is not null),
    constraint onchain_claim_evidence_no_self_supersede
        check (supersedes_id is null or supersedes_id <> id),
    constraint onchain_claim_evidence_rationale_required_when_superseding
        check (supersedes_id is null or length(btrim(coalesce(rationale, ''))) > 0),
    constraint onchain_claim_evidence_source_ext unique (source_system, external_id)
);

comment on table mesta.onchain_claim_evidence is
    'Append-only staging for onchain evidence backing an extracted claim. The row records what the chain reported; the support verdict is a separate decision point downstream.';
comment on column mesta.onchain_claim_evidence.external_id is
    '"<chain>:<evidence-kind>:<subject>[:<scope>]" — the query''s identity, so a repeated check dedupes on (source_system, external_id).';
comment on column mesta.onchain_claim_evidence.claim_ref is
    'Opaque reference to the extracted claim (extraction pipeline''s id). The claim text itself never reaches this table — it is document data, not chain data.';
comment on column mesta.onchain_claim_evidence.observed_payload is
    'The normalized provider response the numeric/text observation was derived from — lineage for re-derivation.';

grant select, insert on mesta.onchain_claim_evidence to "${runtime_role}";

create trigger onchain_claim_evidence_append_only before update or delete on mesta.onchain_claim_evidence
    for each row execute function mesta.reject_mutation();
