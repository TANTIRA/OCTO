#!/usr/bin/env bash
# Upgrade + rollback rehearsal — criterion 2 of ADR-0002's production acceptance list.
#
#   deploy/drill/rollback-rehearsal.sh [evidence-file]
#
# `deploy/README.md` ("Rollback") states the only sanctioned rollback: the compose is
# stateless, migrations are forward-only, and applied migrations are never reverted — you
# roll the code forward. For a migration that has already run and must be undone, the
# sanctioned path is therefore to restore the pre-migration state from the base backup plus
# WAL archive and roll the code back to match. This script rehearses exactly that, so the
# path is known to work before it is needed, and measures how long it takes.
#
# It is parameterised by version so a future slice can rehearse its own V(n-1) -> V(n) bump
# without rewriting the procedure:
#
#   FROM_VERSION=41 TO_VERSION=42 deploy/drill/rollback-rehearsal.sh
#
# Scope note: on a *fresh* database the V28 rewrite already leaves the function body in the
# state V40 pins, so the upgrade is a no-op in effect and the rehearsal asserts convergence
# rather than a visible change. That is a property of the migration, not a gap in the drill.
# A renamed-path database (ops ran `alter schema mesta rename to octo` before boot) is the
# case V40 actually changes; rehearse that on staging, where the history is real.
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

EVIDENCE="${1:-deploy/drill/evidence/rollback-$(date -u +%Y%m%dT%H%M%SZ).md}"
FROM_VERSION="${FROM_VERSION:-39}"
TO_VERSION="${TO_VERSION:-40}"
PRIMARY=octo-rb-primary
RESTORED=octo-rb-restored
SHARED_VOL=octo-rb-shared
ARCHIVE_VOL=octo-rb-archive
SHARED=/shared
ARCHIVE=/archive
RESTORE_DIR="${SHARED}/restore"
BACKUP_DIR="${SHARED}/basebackup"
PROBE_ID="77777777-7777-4777-8777-777777777777"
USER_ID="88888888-8888-4888-8888-888888888888"

BODY="$(mktemp)"
trap 'rm -f "$BODY"; cleanup' EXIT

cleanup() {
  cleanup_container "$PRIMARY"
  cleanup_container "$RESTORED"
  cleanup_volume "$SHARED_VOL"
  cleanup_volume "$ARCHIVE_VOL"
}

say() { log "$*"; printf -- '- %s\n' "$*" >> "$BODY"; }
fail_drill() { printf 'FAIL: %s\n' "$*" | tee -a "$BODY"; }

# The object under upgrade: V40 replaces this function's body. md5 of the body is a stable
# fingerprint to compare across the upgrade and the rollback.
fn_fingerprint() {
  sql_scalar "$1" "$PG_DB" "select md5(pg_get_functiondef('octo.tenant_member_event_rules()'::regprocedure))"
}

# The lock key the body actually takes, read out of the stored definition rather than trusted.
fn_lock_key() {
  sql_scalar "$1" "$PG_DB" "select case when pg_get_functiondef('octo.tenant_member_event_rules()'::regprocedure) like '%octo.tenant_member:%' then 'octo' when pg_get_functiondef('octo.tenant_member_event_rules()'::regprocedure) like '%mesta.tenant_member:%' then 'mesta' else 'unknown' end"
}

ensure_tenant() {
  local c="$1"
  psql_c "$c" "$PG_DB" -c "
    insert into octo.tenant (slug, display_name, source_system, correlation_id)
    values ('drill-tenant', 'Drill Tenant', 'drill', gen_random_uuid())
    on conflict do nothing;" >/dev/null
  sql_scalar "$c" "$PG_DB" "select id from octo.tenant where slug='drill-tenant'"
}

seed_ledger() {
  local c="$1" tenant="$2"
  psql_c "$c" "$PG_DB" -c "
    insert into octo.ledger_event
      (id, external_id, flow_type, monetary_amount, currency_code, occurred_at,
       source_system, actor, ingestion_run_id, correlation_id, tenant_id)
    values ('${PROBE_ID}', 'rollback-probe', 'contribution', 7.00, 'EUR', now(),
            'drill', 'drill-runner', gen_random_uuid(), gen_random_uuid(), '${tenant}');" >/dev/null
  psql_c "$c" "$PG_DB" -c "
    insert into octo.audit_event
      (occurred_at, actor, action, subject_type, subject_id, correlation_id, details)
    values (now(), 'drill-runner', 'ledger.posted', 'ledger_event', '${PROBE_ID}',
            gen_random_uuid(), '{}'::jsonb);" >/dev/null
}

# Functional proof that the upgraded function is live: a second 'granted' for the same
# membership must be rejected by the V8 rule the function carries. Schema presence alone
# would not show that. tenant_member_event FKs to tenant_member, so the header row is
# inserted first, and tenant_member_event_role_iff_access requires a role on a grant.
membership_rule_enforced() {
  local c="$1" tenant="$2" out
  psql_c "$c" "$PG_DB" -c "
    insert into octo.tenant_member (tenant_id, user_id, source_system, correlation_id)
    values ('${tenant}', '${USER_ID}', 'drill', gen_random_uuid())
    on conflict do nothing;" >/dev/null
  psql_c "$c" "$PG_DB" -c "
    insert into octo.tenant_member_event
      (tenant_id, user_id, event_type, role, actor, occurred_at, correlation_id)
    values ('${tenant}', '${USER_ID}', 'granted', 'viewer', 'other-admin', now(), gen_random_uuid());" >/dev/null
  out="$(dexec "$c" psql -U "$PG_USER" -d "$PG_DB" -X -q -c "
    insert into octo.tenant_member_event
      (tenant_id, user_id, event_type, role, actor, occurred_at, correlation_id)
    values ('${tenant}', '${USER_ID}', 'granted', 'viewer', 'other-admin', now(), gen_random_uuid());" 2>&1 || true)"
  if grep -qi 'already has access' <<<"$out"; then echo "enforced"; else echo "NOT-ENFORCED"; fi
}

# --- rehearsal -----------------------------------------------------------------------

require_docker
say "rehearsal start (V${FROM_VERSION} -> V${TO_VERSION}, image ${PG_IMAGE}, commit $(cd "$(repo_root)" && git rev-parse --short HEAD))"

say "step 1/8 start primary with WAL archiving"
cleanup_container "$PRIMARY"; cleanup_container "$RESTORED"
cleanup_volume "$SHARED_VOL"; cleanup_volume "$ARCHIVE_VOL"
docker volume create "$SHARED_VOL" >/dev/null
docker volume create "$ARCHIVE_VOL" >/dev/null
prepare_volumes "$SHARED_VOL" "$ARCHIVE_VOL"
start_primary "$PRIMARY" "$SHARED_VOL" "$ARCHIVE_VOL" drillpw

say "step 2/8 migrate to V${FROM_VERSION} (the state before the upgrade)"
apply_migrations "$PRIMARY" "$FROM_VERSION"
say "V${FROM_VERSION} baseline: flyway_rank=$(sql_scalar "$PRIMARY" "$PG_DB" "select max(installed_rank) from octo.flyway_schema_history")"

say "step 3/8 seed tenant and ledger data, then capture the pre-upgrade baseline"
TENANT_ID="$(ensure_tenant "$PRIMARY")"
seed_ledger "$PRIMARY" "$TENANT_ID"
# Captured after seeding and before the backup, so these are exactly the values the
# rollback must reproduce: same function body, same row counts, same chain.
BASE_LEDGER="$(sql_scalar "$PRIMARY" "$PG_DB" "select count(*) from octo.ledger_event")"
BASE_AUDIT="$(sql_scalar "$PRIMARY" "$PG_DB" "select count(*) from octo.audit_event")"
BASE_FN="$(fn_fingerprint "$PRIMARY")"
BASE_KEY="$(fn_lock_key "$PRIMARY")"
say "pre-upgrade baseline: ledger=${BASE_LEDGER} audit=${BASE_AUDIT} function_md5=${BASE_FN} lock_key=${BASE_KEY}"

say "step 4/8 take the pre-upgrade base backup and mark the rollback point"
docker exec "$PRIMARY" sh -c "rm -rf ${BACKUP_DIR} && mkdir -p ${BACKUP_DIR} && chown postgres:postgres ${BACKUP_DIR}"
dexec "$PRIMARY" pg_basebackup -U "$PG_USER" -D "$BACKUP_DIR" -Ft -z -Xs -c fast >/dev/null
psql_c "$PRIMARY" "$PG_DB" -c "select pg_switch_wal();" >/dev/null
ROLLBACK_TARGET="$(sql_scalar "$PRIMARY" "$PG_DB" "select to_char(clock_timestamp() at time zone 'UTC','YYYY-MM-DD HH24:MI:SS.US')")"
psql_c "$PRIMARY" "$PG_DB" -c "select pg_switch_wal();" >/dev/null
sleep 1
psql_c "$PRIMARY" "$PG_DB" -c "select pg_switch_wal();" >/dev/null
say "base backup taken; rollback target time (UTC): ${ROLLBACK_TARGET}"

say "step 5/8 apply V${TO_VERSION} (the upgrade), then verify it is live and non-destructive"
apply_migrations "$PRIMARY" "$TO_VERSION"
UP_FN="$(fn_fingerprint "$PRIMARY")"
UP_KEY="$(fn_lock_key "$PRIMARY")"
UP_LEDGER="$(sql_scalar "$PRIMARY" "$PG_DB" "select count(*) from octo.ledger_event")"
UP_AUDIT="$(sql_scalar "$PRIMARY" "$PG_DB" "select count(*) from octo.audit_event")"
UP_BROKEN="$(sql_scalar "$PRIMARY" "$PG_DB" "select count(*) from octo.flyway_schema_history where not success")"
RULE="$(membership_rule_enforced "$PRIMARY" "$TENANT_ID")"
say "V${TO_VERSION} state: flyway_rank=$(sql_scalar "$PRIMARY" "$PG_DB" "select max(installed_rank) from octo.flyway_schema_history") ledger=${UP_LEDGER} audit=${UP_AUDIT} failed_migrations=${UP_BROKEN}"
say "function body fingerprint (md5): ${UP_FN}; advisory lock key: ${UP_KEY}; membership rule: ${RULE}"

{
  echo
  echo "## Upgrade assertions"
  echo
  echo "| Assertion | Expected | Observed | Result |"
  echo "| --- | --- | --- | --- |"
  printf '| V%s applied with no failed rows | 0 failed | %s | %s |\n' "$TO_VERSION" "$UP_BROKEN" \
    "$([[ "$UP_BROKEN" == "0" ]] && echo PASS || echo FAIL)"
  printf '| Ledger rows preserved across the upgrade | %s | %s | %s |\n' "$BASE_LEDGER" "$UP_LEDGER" \
    "$([[ "$UP_LEDGER" == "$BASE_LEDGER" ]] && echo PASS || echo FAIL)"
  printf '| Audit rows preserved across the upgrade | %s | %s | %s |\n' "$BASE_AUDIT" "$UP_AUDIT" \
    "$([[ "$UP_AUDIT" == "$BASE_AUDIT" ]] && echo PASS || echo FAIL)"
  printf '| Advisory lock key after upgrade | octo | %s | %s |\n' "$UP_KEY" \
    "$([[ "$UP_KEY" == "octo" ]] && echo PASS || echo FAIL)"
  printf '| V8 membership rule enforced by the upgraded function | enforced | %s | %s |\n' "$RULE" \
    "$([[ "$RULE" == "enforced" ]] && echo PASS || echo FAIL)"
} >> "$BODY"

say "step 6/8 roll back by restoring the pre-upgrade backup to the rollback point"
psql_c "$PRIMARY" "$PG_DB" -c "select pg_switch_wal();" >/dev/null
sleep 1
psql_c "$PRIMARY" "$PG_DB" -c "select pg_switch_wal();" >/dev/null
docker exec "$PRIMARY" sh -c "rm -rf ${RESTORE_DIR} && mkdir -p ${RESTORE_DIR} && chown postgres:postgres ${RESTORE_DIR}"
docker exec "$PRIMARY" sh -c "
  set -e
  for f in ${BACKUP_DIR}/*.tar.gz; do tar -xzf \"\$f\" -C ${RESTORE_DIR}; done
  rm -f ${RESTORE_DIR}/backup_manifest
  cp -a ${ARCHIVE}/wal/. ${RESTORE_DIR}/pg_wal/ 2>/dev/null || true
  chown -R postgres:postgres ${RESTORE_DIR}
  chmod 700 ${RESTORE_DIR}
  {
    echo \"restore_command = 'cp ${ARCHIVE}/wal/%f %p'\"
    echo \"recovery_target_time = '${ROLLBACK_TARGET} UTC'\"
    echo \"recovery_target_action = 'promote'\"
  } >> ${RESTORE_DIR}/postgresql.auto.conf
  touch ${RESTORE_DIR}/recovery.signal
"

say "step 7/8 start the restored instance and time the rollback (RTO)"
cleanup_container "$RESTORED"
start_data_container "$RESTORED" "$SHARED_VOL" "$ARCHIVE_VOL" "$RESTORE_DIR"
RTO_START="$(date -u +%s%3N)"
if ! wait_accepting "$RESTORED" "$PG_DB" 600; then
  docker logs "$RESTORED" 2>&1 | tail -40 | tee -a "$BODY"
  fail_drill "restored instance never reached 'ready'"
  exit 1
fi
RTO_MS=$(( $(date -u +%s%3N) - RTO_START ))
say "rolled-back instance reached 'ready' in ${RTO_MS} ms"

say "step 8/8 verify the rolled-back state matches the pre-upgrade state"
RB_FN="$(fn_fingerprint "$RESTORED")"
RB_KEY="$(fn_lock_key "$RESTORED")"
RB_LEDGER="$(sql_scalar "$RESTORED" "$PG_DB" "select count(*) from octo.ledger_event")"
RB_AUDIT="$(sql_scalar "$RESTORED" "$PG_DB" "select count(*) from octo.audit_event")"
RB_RANK="$(sql_scalar "$RESTORED" "$PG_DB" "select coalesce(max(installed_rank),0) from octo.flyway_schema_history")"
RB_FAILED="$(sql_scalar "$RESTORED" "$PG_DB" "select count(*) from octo.flyway_schema_history where not success")"
CHAIN="$(cat "$(repo_root)/deploy/drill/sql/verify-audit-chain.sql" \
  | dexec "$RESTORED" psql -U "$PG_USER" -d "$PG_DB" -X -q -tA -v ON_ERROR_STOP=1 -f -)"
CHAIN_STATE="${CHAIN%%|*}"

{
  echo
  echo "## Rollback assertions (restore to the pre-upgrade point)"
  echo
  echo "| Assertion | Expected | Observed | Result |"
  echo "| --- | --- | --- | --- |"
  printf '| Function body matches the pre-upgrade fingerprint | %s | %s | %s |\n' "$BASE_FN" "$RB_FN" \
    "$([[ "$RB_FN" == "$BASE_FN" ]] && echo PASS || echo FAIL)"
  printf '| Advisory lock key matches pre-upgrade | %s | %s | %s |\n' "$BASE_KEY" "$RB_KEY" \
    "$([[ "$RB_KEY" == "$BASE_KEY" ]] && echo PASS || echo FAIL)"
  printf '| Ledger rows match pre-upgrade | %s | %s | %s |\n' "$BASE_LEDGER" "$RB_LEDGER" \
    "$([[ "$RB_LEDGER" == "$BASE_LEDGER" ]] && echo PASS || echo FAIL)"
  printf '| Audit rows match pre-upgrade | %s | %s | %s |\n' "$BASE_AUDIT" "$RB_AUDIT" \
    "$([[ "$RB_AUDIT" == "$BASE_AUDIT" ]] && echo PASS || echo FAIL)"
  printf '| No failed migrations after rollback | 0 | %s | %s |\n' "$RB_FAILED" \
    "$([[ "$RB_FAILED" == "0" ]] && echo PASS || echo FAIL)"
  printf '| Audit chain intact after rollback | INTACT | %s | %s |\n' "$CHAIN_STATE" \
    "$([[ "$CHAIN_STATE" == "INTACT" || "$CHAIN_STATE" == "EMPTY" ]] && echo PASS || echo FAIL)"
} >> "$BODY"

{
  echo
  echo "## Measurements"
  echo
  echo "| Metric | Target | Measured in this rehearsal | How it was derived |"
  echo "| --- | --- | --- | --- |"
  echo "| Rollback RTO | ≤ 4 h (restore-runbook §1) | ${RTO_MS} ms | wall clock from starting the restored instance to its first successful query |"
  echo "| Rollback point fidelity | exact | function fingerprint, row counts, and audit chain all match the pre-upgrade state | compared restored values against the values captured before V${TO_VERSION} was applied |"
  echo
  echo "The upgrade itself is forward-only: applying V${TO_VERSION} is not undone by a down-migration,"
  echo "because the repo does not write them (deploy/README.md, \"Rollback\"). Rolling back means"
  echo "restoring the pre-upgrade state and rolling the *code* back to match."
  echo
  echo "Local container timings are not staging evidence. Re-measure on staging, against the real"
  echo "environment's history, before ticking ADR-0002."
} >> "$BODY"

mkdir -p "$(dirname "$EVIDENCE")"
evidence_header "$EVIDENCE" "rollback" "local-docker (not staging)" "$BODY"
log "evidence written to $EVIDENCE"

if grep -q '| FAIL' "$BODY"; then
  fail_drill "one or more assertions failed — see $EVIDENCE"
  exit 1
fi
log "rollback rehearsal PASSED"
