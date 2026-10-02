#!/usr/bin/env bash
# Restore drill — PostgreSQL base backup + continuous WAL archive + point-in-time restore.
#
# Rehearses docs/restore-runbook.md §4 and §7 against throwaway containers, and measures
# RPO and RTO by the same arithmetic the staging rehearsal uses. The record format it writes
# is docs/drill-evidence-template.md.
#
#   deploy/drill/restore-drill.sh [evidence-file]
#
# This is preparation, not acceptance. It proves the procedure works and that recovery lands
# on the intended point in time. It cannot prove staging timings or that production backups
# exist — ADR-0002's acceptance boxes are ticked only from a filled record on staging.
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

EVIDENCE="${1:-deploy/drill/evidence/restore-$(date -u +%Y%m%dT%H%M%SZ).md}"
PRIMARY=octo-drill-primary
RESTORED=octo-drill-restored
SHARED_VOL=octo-drill-shared
ARCHIVE_VOL=octo-drill-archive
SHARED=/shared
ARCHIVE=/archive
DATADIR="${SHARED}/primary"
RESTORE_DIR="${SHARED}/restore"
BACKUP_DIR="${SHARED}/basebackup"
PASSWORD=drillpw

# Deterministic ids, so the restored database is checked row by row and not just by count.
LEDGER_IDS=(
  "11111111-1111-4111-8111-111111111111"
  "22222222-2222-4222-8222-222222222222"
)
POST_LOSS_ID="99999999-9999-4999-8999-999999999999"

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

# A tenant must exist first: V34's ledger_event_key_requires_tenant check rejects a keyed
# ledger row with a null tenant, and V30 puts ledger_event under RLS.
ensure_tenant() {
  local c="$1"
  psql_c "$c" "$PG_DB" -c "
    insert into octo.tenant (slug, display_name, source_system, correlation_id)
    values ('drill-tenant', 'Drill Tenant', 'drill', gen_random_uuid())
    on conflict do nothing;" >/dev/null
  sql_scalar "$c" "$PG_DB" "select id from octo.tenant where slug='drill-tenant'"
}

seed() {
  # Split deliberately: bash evaluates every right-hand side of a single `local` before
  # assigning any of them, so `n` would still be unset when the index is computed.
  local c="$1" n="$2"
  local id="${LEDGER_IDS[$((n - 1))]}"
  local tenant="$3"
  psql_c "$c" "$PG_DB" -c "
    insert into octo.ledger_event
      (id, external_id, flow_type, monetary_amount, currency_code, occurred_at,
       source_system, actor, ingestion_run_id, correlation_id, tenant_id)
    values ('${id}', 'drill-${n}', 'contribution', 1000.00 * ${n}, 'EUR', now(),
            'drill', 'drill-runner', gen_random_uuid(), gen_random_uuid(), '${tenant}');" >/dev/null
  psql_c "$c" "$PG_DB" -c "
    insert into octo.audit_event
      (occurred_at, actor, action, subject_type, subject_id, correlation_id, details)
    values (now(), 'drill-runner', 'ledger.posted', 'ledger_event', '${id}',
            gen_random_uuid(), '{}'::jsonb);" >/dev/null
}

# Highest LSN the primary has archived, as a number, so we can wait for the archive to catch up.
archived_max_lsn() {
  dexec "$PRIMARY" sh -c "ls ${ARCHIVE}/wal 2>/dev/null | sort | tail -1" | tr -d '\r'
}

# Wait until a WAL segment has actually landed in the archive, so the restore has a stream
# to replay. WAL names are 24 zero-padded hex digits, so lexical order is numeric order.
wait_archive_caught_up() {
  local i have
  for ((i = 0; i < 60; i++)); do
    have="$(archived_max_lsn)"
    [[ -n "$have" ]] && return 0
    sleep 0.5
  done
  return 1
}

# --- drill ---------------------------------------------------------------------------

require_docker
say "drill start (image ${PG_IMAGE}, commit $(cd "$(repo_root)" && git rev-parse --short HEAD))"

say "step 1/9 prepare volumes and start primary with WAL archiving"
cleanup_container "$PRIMARY"; cleanup_container "$RESTORED"
cleanup_volume "$SHARED_VOL"; cleanup_volume "$ARCHIVE_VOL"
docker volume create "$SHARED_VOL" >/dev/null
docker volume create "$ARCHIVE_VOL" >/dev/null
prepare_volumes "$SHARED_VOL" "$ARCHIVE_VOL"
start_primary "$PRIMARY" "$SHARED_VOL" "$ARCHIVE_VOL" "$PASSWORD"

say "step 2/9 apply the real migration chain (V1..V$(migration_top))"
apply_migrations "$PRIMARY"

# The drill grades a restore with the SQL mirror of verifyAuditChain, so the mirror has to be
# shown strict before its verdict means anything. A verifier that only ever returns INTACT
# would pass every drill above and be worthless. This is the self-test, and it runs before
# the restore so a broken tool can never produce an evidence record.
say "step 2b/9 self-test the audit-chain verifier (must detect forgery, not just pass)"
TENANT_ID="$(ensure_tenant "$PRIMARY")"
SELFTEST_VERDICTS=()
run_verify() { cat "$(repo_root)/deploy/drill/sql/verify-audit-chain.sql" \
  | dexec "$PRIMARY" psql -U "$PG_USER" -d "$PG_DB" -X -q -tA -f - | head -1 | cut -d'|' -f1; }

# Every case below is derived from a chain the trigger actually wrote, by mutating it. Nothing
# is hand-built: audit_event_chain owns seq, prev_hash and hash, so a synthesised row has to
# reproduce the canonical form exactly, and a test that gets that wrong tests only itself.

# (a) a chain written through the trigger must verify. The log is empty after the migration, so
# seed two rows through the real write path first; they are torn down when the self-test ends.
enable_audit_chain "$PRIMARY"
psql_c "$PRIMARY" "$PG_DB" -c "
  insert into octo.audit_event (occurred_at, actor, action, subject_type, subject_id, correlation_id, details)
  values (now(), 'drill-runner', 'selftest.first', 'audit_event', 'selftest-1', gen_random_uuid(), '{}'::jsonb);" >/dev/null
SELFTEST_VERDICTS+=("clean:$(run_verify)")

# (b) a changed stored hash must break it
psql_c "$PRIMARY" "$PG_DB" -c "alter table octo.audit_event disable trigger audit_event_append_only;" >/dev/null
psql_c "$PRIMARY" "$PG_DB" -c "update octo.audit_event set hash = decode(repeat('cd',32),'hex') where seq = 1;" >/dev/null
SELFTEST_VERDICTS+=("altered-hash:$(run_verify)")
psql_c "$PRIMARY" "$PG_DB" -c "alter table octo.audit_event enable trigger audit_event_append_only;" >/dev/null

# (c) a hash computed over the WRONG canonical must be rejected, while the chain stays
# internally consistent (each prev_hash still matches the previous row's hash). This is the
# case that proves the recomputation is strict rather than merely comparing links.
reset_audit_chain "$PRIMARY"
psql_c "$PRIMARY" "$PG_DB" -c "
  insert into octo.audit_event
    (seq, occurred_at, recorded_at, actor, action, subject_type, subject_id,
     correlation_id, details, prev_hash, hash)
  values (1, now(), now(), 'drill-runner', 'forged', 'audit_event', 'forged-1',
          gen_random_uuid(), '{}'::jsonb, decode(repeat('00',32),'hex'),
          sha256(decode(repeat('00',32),'hex') || convert_to('WRONG-CANONICAL','UTF8')));" >/dev/null
SELFTEST_VERDICTS+=("forged-canonical:$(run_verify)")

# (d) a chain missing a middle row must be reported. Clear (c)'s forgery, hand the log back to
# the trigger, seed three genuine rows, then remove only the middle one — leaving seq 1 and 3.
# Three, not two: deleting the last row of a two-row log is caught by the genesis anchor rather
# than by the gap check, which would mean this case was testing something other than a hole.
reset_audit_chain "$PRIMARY"
enable_audit_chain "$PRIMARY"
psql_c "$PRIMARY" "$PG_DB" -c "
  insert into octo.audit_event (occurred_at, actor, action, subject_type, subject_id, correlation_id, details)
  values (now(), 'drill-runner', 'selftest.second', 'audit_event', 'selftest-2', gen_random_uuid(), '{}'::jsonb),
         (now(), 'drill-runner', 'selftest.third',  'audit_event', 'selftest-3', gen_random_uuid(), '{}'::jsonb),
         (now(), 'drill-runner', 'selftest.fourth', 'audit_event', 'selftest-4', gen_random_uuid(), '{}'::jsonb);" >/dev/null
psql_c "$PRIMARY" "$PG_DB" -c "
  alter table octo.audit_event disable trigger audit_event_append_only;
  delete from octo.audit_event where seq = 2;
  alter table octo.audit_event enable trigger audit_event_append_only;" >/dev/null
SELFTEST_VERDICTS+=("seq-gap:$(run_verify)")

# Hand the log back to the trigger, empty, so the drill's own data flows through the real write
# path and starts a fresh valid chain at seq 1.
reset_audit_chain "$PRIMARY"
enable_audit_chain "$PRIMARY"

SELFTEST="PASS"
[[ "${SELFTEST_VERDICTS[0]}" == "clean:INTACT" ]] || SELFTEST="FAIL"
for v in "${SELFTEST_VERDICTS[@]:1}"; do
  [[ "${v#*:}" == "BROKEN" ]] || SELFTEST="FAIL"
done
say "verifier self-test: ${SELFTEST} (${SELFTEST_VERDICTS[*]})"
if [[ "$SELFTEST" != "PASS" ]]; then
  fail_drill "the audit-chain verifier did not behave as required; refusing to grade a restore with it"
  exit 1
fi

say "step 3/9 seed a tenant, then data and audit rows through the V6 chain trigger"
seed "$PRIMARY" 1 "$TENANT_ID"

say "step 4/9 take the base backup (pg_basebackup -Ft, checksums on)"
docker exec "$PRIMARY" sh -c "rm -rf ${BACKUP_DIR} && mkdir -p ${BACKUP_DIR} && chown postgres:postgres ${BACKUP_DIR}"
dexec "$PRIMARY" pg_basebackup -U "$PG_USER" -D "$BACKUP_DIR" -Ft -z -Xs -c fast >/dev/null
say "base backup taken: $(dexec "$PRIMARY" sh -c "ls -1 ${BACKUP_DIR} | tr '\n' ' '")"

say "step 5/9 commit the boundary row, then force WAL archiving to catch up"
seed "$PRIMARY" 2 "$TENANT_ID"
psql_c "$PRIMARY" "$PG_DB" -c "select pg_switch_wal();" >/dev/null
# The restore target is taken after the switch and before any post-loss write, so the drill
# asserts on both sides of it: the boundary row must come back, the post-loss row must not.
RECOVERY_TARGET="$(sql_scalar "$PRIMARY" "$PG_DB" "select to_char(clock_timestamp() at time zone 'UTC','YYYY-MM-DD HH24:MI:SS.US')")"
say "recovery target time (UTC): ${RECOVERY_TARGET}"
psql_c "$PRIMARY" "$PG_DB" -c "select pg_switch_wal();" >/dev/null
sleep 1
psql_c "$PRIMARY" "$PG_DB" -c "select pg_switch_wal();" >/dev/null
wait_archive_caught_up || warn "no WAL segment reached the archive; continuing"

PRE_LEDGER="$(sql_scalar "$PRIMARY" "$PG_DB" "select count(*) from octo.ledger_event")"
PRE_AUDIT="$(sql_scalar "$PRIMARY" "$PG_DB" "select count(*) from octo.audit_event")"
PRE_FLYWAY="$(sql_scalar "$PRIMARY" "$PG_DB" "select coalesce(max(installed_rank),0) from octo.flyway_schema_history")"
# The whole history set, not just its head: a high-water mark passes whether a middle
# row was deleted or an early script rewritten. The md5 over the ordered
# installed_rank|version|script set detects both.
PRE_FLYWAY_MD5="$(sql_scalar "$PRIMARY" "$PG_DB" "select md5(string_agg(installed_rank::text || '|' || version || '|' || script, E'\n' order by installed_rank)) from octo.flyway_schema_history")"
say "pre-loss high-water marks: ledger=${PRE_LEDGER} audit=${PRE_AUDIT} flyway_rank=${PRE_FLYWAY} history_md5=${PRE_FLYWAY_MD5}"

say "step 6/9 write post-loss data that the restore must NOT bring back"
psql_c "$PRIMARY" "$PG_DB" -c "
  insert into octo.ledger_event
    (id, external_id, flow_type, monetary_amount, currency_code, occurred_at,
     source_system, actor, ingestion_run_id, correlation_id, tenant_id)
  values ('${POST_LOSS_ID}', 'drill-post-loss', 'distribution', 42.00, 'EUR', now(),
          'drill', 'drill-runner', gen_random_uuid(), gen_random_uuid(), '${TENANT_ID}');" >/dev/null
psql_c "$PRIMARY" "$PG_DB" -c "select pg_switch_wal();" >/dev/null
say "post-loss row written; primary now has $(sql_scalar "$PRIMARY" "$PG_DB" "select count(*) from octo.ledger_event") ledger rows"

say "step 7/9 restore the base backup into a separate data directory"
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
    echo \"recovery_target_time = '${RECOVERY_TARGET} UTC'\"
    echo \"recovery_target_action = 'promote'\"
  } >> ${RESTORE_DIR}/postgresql.auto.conf
  touch ${RESTORE_DIR}/recovery.signal
"
say "restore staged into ${RESTORE_DIR} with recovery_target_time=${RECOVERY_TARGET}"

say "step 8/9 start the restored instance and time recovery to accepting queries (RTO)"
cleanup_container "$RESTORED"
start_data_container "$RESTORED" "$SHARED_VOL" "$ARCHIVE_VOL" "$RESTORE_DIR"
RTO_START="$(date -u +%s%3N)"
if ! wait_accepting "$RESTORED" "$PG_DB" 600; then
  docker logs "$RESTORED" 2>&1 | tail -40 | tee -a "$BODY"
  fail_drill "restored instance never reached 'ready'"
  exit 1
fi
READY_MS=$(( $(date -u +%s%3N) - RTO_START ))
say "restored instance reached 'ready' in ${READY_MS} ms"
RESTORED_LAST="$(sql_scalar "$RESTORED" "$PG_DB" "select to_char(max(occurred_at) at time zone 'UTC','YYYY-MM-DD HH24:MI:SS.US') from octo.ledger_event")"
PRIMARY_LAST="$(sql_scalar "$PRIMARY" "$PG_DB" "select to_char(max(occurred_at) at time zone 'UTC','YYYY-MM-DD HH24:MI:SS.US') from octo.ledger_event where id <> '${POST_LOSS_ID}'")"
say "newest ledger row: restored=${RESTORED_LAST} expected=${PRIMARY_LAST}"

say "step 9/9 run the post-restore checklist (restore-runbook §7)"
{
  echo
  echo "## Verifier self-test"
  echo
  echo "The SQL mirror of \`verifyAuditChain\` grades this drill, so it is shown strict first."
  echo
  echo "| Case | Required verdict | Observed | Result |"
  echo "| --- | --- | --- | --- |"
  printf '| Trigger-written chain | INTACT | %s | %s |\n' "${SELFTEST_VERDICTS[0]#*:}" \
    "$([[ "${SELFTEST_VERDICTS[0]}" == "clean:INTACT" ]] && echo PASS || echo FAIL)"
  for v in "${SELFTEST_VERDICTS[@]:1}"; do
    printf '| %s | BROKEN | %s | %s |\n' "${v%%:*}" "${v#*:}" \
      "$([[ "${v#*:}" == "BROKEN" ]] && echo PASS || echo FAIL)"
  done
  echo
  echo "## Post-restore checklist"
  echo
  printf '| Check | Result |\n| --- | --- |\n'
} >> "$BODY"

# §7.1 flyway_schema_history verifies — no failed rows, and the full
# installed_rank|version|script set matches the pre-loss baseline, so a deleted middle
# row or a rewritten early script fails rather than hiding behind the high-water mark.
{
  printf '| `flyway_schema_history` verifies | %s |\n' \
    "$(sql_scalar "$RESTORED" "$PG_DB" "select case when count(*) filter (where not success)=0 and md5(string_agg(installed_rank::text || '|' || version || '|' || script, E'\n' order by installed_rank)) = '${PRE_FLYWAY_MD5}' then 'PASS' else 'FAIL' end from octo.flyway_schema_history")"
} >> "$BODY"

# §7.2 audit chain verification passes
CHAIN="$(cat "$(repo_root)/deploy/drill/sql/verify-audit-chain.sql" \
  | dexec "$RESTORED" psql -U "$PG_USER" -d "$PG_DB" -X -q -tA -v ON_ERROR_STOP=1 -f -)"
CHAIN_STATE="${CHAIN%%|*}"
{
  printf '| Audit chain verifies (`verifyAuditChain`, V6) | %s (%s) |\n' \
    "$([[ "$CHAIN_STATE" == "INTACT" || "$CHAIN_STATE" == "EMPTY" ]] && echo PASS || echo FAIL)" "$CHAIN"
} >> "$BODY"

# §7.3 row counts and identity of the boundary rows
RESTORED_LEDGER="$(sql_scalar "$RESTORED" "$PG_DB" "select count(*) from octo.ledger_event")"
RESTORED_AUDIT="$(sql_scalar "$RESTORED" "$PG_DB" "select count(*) from octo.audit_event")"
POST_LOSS_PRESENT="$(sql_scalar "$RESTORED" "$PG_DB" "select count(*) from octo.ledger_event where id='${POST_LOSS_ID}'")"
MISSING_IDS="$(sql_scalar "$RESTORED" "$PG_DB" "select count(*) from octo.ledger_event where id in ('${LEDGER_IDS[0]}','${LEDGER_IDS[1]}')")"

COUNT_VERDICT="FAIL"
[[ "$RESTORED_LEDGER" == "$PRE_LEDGER" && "$RESTORED_AUDIT" == "$PRE_AUDIT" ]] && COUNT_VERDICT="PASS"
{
  printf '| Ledger row count matches pre-loss | %s (%s vs %s) |\n' "$COUNT_VERDICT" "$RESTORED_LEDGER" "$PRE_LEDGER"
  printf '| Audit row count matches pre-loss | %s (%s vs %s) |\n' \
    "$([[ "$RESTORED_AUDIT" == "$PRE_AUDIT" ]] && echo PASS || echo FAIL)" "$RESTORED_AUDIT" "$PRE_AUDIT"
  printf '| Boundary row survived the restore | %s |\n' \
    "$([[ "$MISSING_IDS" == "2" ]] && echo PASS || echo FAIL)"
  printf '| Post-loss row is gone | %s |\n' \
    "$([[ "$POST_LOSS_PRESENT" == "0" ]] && echo PASS || echo FAIL)"
} >> "$BODY"

# §7.4 append-only controls and role separation survived. Queried as individual scalars
# rather than one wide row, so the evidence reads as named checks instead of a delimited dump.
check_sql() { psql_c "$RESTORED" "$PG_DB" -tAc "$1"; }
LEDGER_TRIG="$(check_sql "select count(*) from pg_trigger t join pg_class c on c.oid=t.tgrelid join pg_namespace n on n.oid=c.relnamespace where n.nspname='octo' and c.relname='ledger_event' and t.tgname='ledger_event_append_only' and not t.tgisinternal and t.tgenabled in ('O','A')")"
AUDIT_TRIG="$(check_sql "select count(*) from pg_trigger t join pg_class c on c.oid=t.tgrelid join pg_namespace n on n.oid=c.relnamespace where n.nspname='octo' and c.relname='audit_event' and t.tgname='audit_event_append_only' and not t.tgisinternal and t.tgenabled in ('O','A')")"
MUTATION_FN="$(check_sql "select count(*) from pg_proc p join pg_namespace n on n.oid=p.pronamespace where n.nspname='octo' and p.proname='ledger_event_reject_mutation'")"
ROLE_OK="$(check_sql "select case when exists (select 1 from pg_roles where rolname='${RUNTIME_ROLE}' and not rolsuper) then 'present and not superuser' else 'MISSING OR SUPERUSER' end")"
{
  printf '| Ledger append-only trigger survived (enabled) | %s |\n' \
    "$([[ "$LEDGER_TRIG" == "1" ]] && echo PASS || echo FAIL)"
  printf '| Audit append-only trigger survived (enabled) | %s |\n' \
    "$([[ "$AUDIT_TRIG" == "1" ]] && echo PASS || echo FAIL)"
  printf '| Append-only enforcement function present | %s |\n' \
    "$([[ "$MUTATION_FN" == "1" ]] && echo PASS || echo FAIL)"
  printf '| Runtime role restored without elevation | %s (%s) |\n' \
    "$([[ "$ROLE_OK" == "present and not superuser" ]] && echo PASS || echo FAIL)" "$ROLE_OK"
} >> "$BODY"

# The same checks as one database-side verdict, so the SQL file is exercised and not merely
# kept alongside. The query returns one wide row; the verdict is its first field.
CHECK_ROW="$(cat "$(repo_root)/deploy/drill/sql/post-restore-check.sql" \
  | dexec "$RESTORED" psql -U "$PG_USER" -d "$PG_DB" -X -q -tA -v ON_ERROR_STOP=1 \
      -v exp_ledger="$PRE_LEDGER" -v exp_audit="$PRE_AUDIT" -v exp_flyway="$PRE_FLYWAY" \
      -v exp_flyway_md5="$PRE_FLYWAY_MD5" \
      -v runtime_role="$RUNTIME_ROLE" -f - | head -1)"
VERDICT="${CHECK_ROW%%|*}"
{
  printf '| `sql/post-restore-check.sql` verdict | %s (%s) |\n' \
    "$([[ "$VERDICT" == "PASS" ]] && echo PASS || echo FAIL)" "$VERDICT"
} >> "$BODY"

# Per the runbook, RTO ends when the restored instance is ready *and* the §7 checklist
# has passed — the timer that stopped at the first query understated it.
RTO_MS=$(( $(date -u +%s%3N) - RTO_START ))
say "post-restore checklist complete at ${RTO_MS} ms after restored-instance start (RTO)"

# RPO and RTO. The drill proves point-in-time fidelity — the boundary row committed
# before the target came back, the post-loss row did not — which is not the same as a
# measured RPO under a real archive interval: here the loss window is bounded above by
# the archive_timeout this drill configures (30 s), and the real number is measured on
# staging under its own interval. RTO is readiness plus the §7 checklist, per the
# runbook's definition, not merely the first answered query.
{
  echo
  echo "## Measurements"
  echo
  echo "| Metric | Target | Measured in this drill | How it was derived |"
  echo "| --- | --- | --- | --- |"
  echo "| RPO | ≤ 15 min (restore-runbook §1) | recovery landed exactly on the target: boundary row present, post-loss row absent | point-in-time fidelity; the loss window is bounded by archive_timeout (30 s in this drill), so the measured RPO on staging is bounded by its real archive interval |"
  echo "| RTO | ≤ 4 h (restore-runbook §1) | ${RTO_MS} ms (of which ${READY_MS} ms to first query) | wall clock from restored-instance start until the §7 checklist completed |"
  echo
  echo "Local container timings are not staging evidence. The RTO figure here bounds the"
  echo "*procedure*, not production, which also carries backup download, verification, and"
  echo "repointing the application. Re-measure on staging before ticking ADR-0002."
} >> "$BODY"

mkdir -p "$(dirname "$EVIDENCE")"
evidence_header "$EVIDENCE" "restore" "local-docker (not staging)" "$BODY"
log "evidence written to $EVIDENCE"

# The drill exits non-zero when the substance failed, so CI or a shell chain can gate on it.
if grep -q '| FAIL' "$BODY" || [[ "$CHAIN_STATE" != "INTACT" && "$CHAIN_STATE" != "EMPTY" ]]; then
  fail_drill "one or more post-restore checks failed — see $EVIDENCE"
  exit 1
fi
log "restore drill PASSED"
