-- V2__decision_staging.sql
-- Staging tables for decision-model outputs produced during ingestion.
--
-- Grounded in:
--   ADR-0001                            PostgreSQL owns the ledger of record; TypeDB is rebuilt from it
--   ADR-0002                            domain schemas stay isolated from Supabase internal schemas
--   data-security-governance.md         "Every event records source, actor, ingestion run, effective
--                                        time, recorded time, currency, and correlation ID"
--   docs/decision-model-integration-map.md  integration points 5 (document classification) and
--                                        6 (claim support)
--   ontology/mesta-investment.tql       document-type @values, document-sha256 @regex,
--                                        confidence-level @range(0..1)
--
-- Decisions are facts, not mutable state: a re-classification produces a new row, a correction
-- produces a row that supersedes the earlier one. Both tables are therefore append-only like
-- mesta.ledger_event. This migration is self-contained so it applies cleanly whether or not
-- V1__init.sql has already run.

create schema if not exists mesta;

-- Append-only enforcement for the decision tables. Mirrors V1's ledger trigger but is generic over
-- the table name so both staging tables share it.
create or replace function mesta.reject_mutation() returns trigger
    language plpgsql
as $$
begin
    raise exception
        'mesta.% is append-only: % rejected. Record a correcting row instead.',
        tg_table_name, lower(tg_op)
        using errcode = 'restrict_violation',
              hint = 'Insert a new row with supersedes_id pointing at the row you meant to change.';
end;
$$;

comment on function mesta.reject_mutation() is
    'Trigger function that blocks UPDATE and DELETE on append-only mesta tables.';

-- Integration map point 5: what the classifier decided for an ingested document, including the
-- full probability distribution so a low-confidence outcome can be reviewed rather than silently
-- accepted. Writes go to document.document-type in TypeDB only after review.
create table mesta.document_classification (
    id                   uuid             primary key default gen_random_uuid(),
    external_id          text,
    document_sha256      char(64)         not null,
    document_type        text             not null,
    confidence           double precision not null,
    distribution         jsonb            not null,
    requires_review      boolean          not null,
    model_provider       text,
    model_version        text             not null,
    decision_request_id  text,
    recorded_at          timestamptz      not null default now(),
    supersedes_id        uuid             references mesta.document_classification (id),
    rationale            text,
    source_system        text             not null,
    actor                text             not null,
    ingestion_run_id     uuid             not null,
    correlation_id       uuid             not null,

    constraint document_classification_type_known check (document_type in (
        'pitch-deck',
        'ddq',
        'financials',
        'icap-report',
        'memo',
        'legal',
        'lp-report',
        'tear-sheet',
        'other'
    )),
    constraint document_classification_sha256_shape
        check (document_sha256 ~ '^[0-9a-f]{64}$'),
    constraint document_classification_confidence_range
        check (confidence between 0 and 1),
    constraint document_classification_no_self_supersede
        check (supersedes_id is null or supersedes_id <> id),
    constraint document_classification_rationale_required_when_superseding
        check (supersedes_id is null or length(btrim(coalesce(rationale, ''))) > 0)
);

comment on table mesta.document_classification is
    'Append-only staging for document-type decisions. Mirrors document-type @values; the TypeDB document.document-type attribute is written downstream after review.';
comment on column mesta.document_classification.distribution is
    'Full probability distribution over the nine document types, serialized as {"pitch-deck":0.62,...}. The argmax alone cannot show how marginal a decision was.';
comment on column mesta.document_classification.requires_review is
    'True when confidence fell below the review threshold or the model returned an unmapped option. Humans review; the flag is never overwritten.';
comment on column mesta.document_classification.model_version is
    'The model that produced the decision, e.g. typesafe/jev-1.13. decision-model-integration-map.md requires this lineage on every AI-supported decision.';
comment on column mesta.document_classification.decision_request_id is
    'Provider-side request id for the decision call, when the provider returns one.';

create index document_classification_document_idx
    on mesta.document_classification (document_sha256);
create index document_classification_review_idx
    on mesta.document_classification (requires_review)
    where requires_review;
create index document_classification_supersedes_idx
    on mesta.document_classification (supersedes_id)
    where supersedes_id is not null;

-- Idempotent ingestion: a replayed source record must not insert twice.
create unique index document_classification_source_external_key
    on mesta.document_classification (source_system, external_id)
    where external_id is not null;

create trigger document_classification_append_only
    before update or delete on mesta.document_classification
    for each row
    execute function mesta.reject_mutation();

-- Integration map point 6: whether a quoted passage supports an extracted claim. The policy that
-- produced the verdict is stored alongside it so the row stays reproducible when thresholds move.
-- support_probability is the value written to extracted-claim.confidence-level downstream.
create table mesta.claim_assessment (
    id                      uuid             primary key default gen_random_uuid(),
    external_id             text,
    claim_text              text             not null,
    source_document_sha256  char(64),
    support_probability     double precision not null,
    support_threshold       double precision not null,
    review_band             double precision not null,
    supported               boolean          not null,
    requires_review         boolean          not null,
    model_provider          text,
    model_version           text             not null,
    decision_request_id     text,
    recorded_at             timestamptz      not null default now(),
    supersedes_id           uuid             references mesta.claim_assessment (id),
    rationale               text,
    source_system           text             not null,
    actor                   text             not null,
    ingestion_run_id        uuid             not null,
    correlation_id          uuid             not null,

    constraint claim_assessment_document_sha256_shape
        check (source_document_sha256 is null or source_document_sha256 ~ '^[0-9a-f]{64}$'),
    constraint claim_assessment_probability_range
        check (support_probability between 0 and 1),
    constraint claim_assessment_threshold_range
        check (support_threshold between 0 and 1),
    constraint claim_assessment_review_band_range
        check (review_band >= 0),
    constraint claim_assessment_no_self_supersede
        check (supersedes_id is null or supersedes_id <> id),
    constraint claim_assessment_rationale_required_when_superseding
        check (supersedes_id is null or length(btrim(coalesce(rationale, ''))) > 0)
);

comment on table mesta.claim_assessment is
    'Append-only staging for claim-support decisions. The verdict is a probability plus the policy snapshot that produced it, never a silent boolean.';
comment on column mesta.claim_assessment.claim_text is
    'The claim as assessed. The TypeDB extracted-claim entity links back through extraction-source downstream.';
comment on column mesta.claim_assessment.support_threshold is
    'The support threshold in force when the decision was made (ClaimSupportPolicy.supportThreshold). Stored per row so results stay reproducible.';
comment on column mesta.claim_assessment.review_band is
    'Half-width of the ambiguity band around the threshold. Rows whose probability sits inside the band carry requires_review = true.';
comment on column mesta.claim_assessment.supported is
    'support_probability >= support_threshold at decision time. Marginal results keep the verdict but are routed to review.';
comment on column mesta.claim_assessment.model_version is
    'The model that produced the decision, e.g. typesafe/jev-1.13.';

create index claim_assessment_document_idx
    on mesta.claim_assessment (source_document_sha256)
    where source_document_sha256 is not null;
create index claim_assessment_review_idx
    on mesta.claim_assessment (requires_review)
    where requires_review;
create index claim_assessment_supersedes_idx
    on mesta.claim_assessment (supersedes_id)
    where supersedes_id is not null;

create unique index claim_assessment_source_external_key
    on mesta.claim_assessment (source_system, external_id)
    where external_id is not null;

create trigger claim_assessment_append_only
    before update or delete on mesta.claim_assessment
    for each row
    execute function mesta.reject_mutation();
