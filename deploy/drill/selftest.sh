#!/usr/bin/env bash
# Self-audit for the rehearsal harness: does the tooling actually behave as it claims?
#
#   deploy/drill/selftest.sh
#
# The drills grade a restore with the SQL mirror of `verifyAuditChain`. A verifier that only
# ever answered INTACT would pass every drill and be worthless, so this asserts the mirror's
# behaviour directly, then checks the artifacts around it: evidence records, the ADR boxes,
# line endings, executable bits, and that every document link resolves.
#
# Exits non-zero if anything fails. Run it after changing the harness, the verifier, or the docs.
#
# Three of these checks were wrong when first written, and each was wrong in an instructive way:
#   * a forged hash does not survive unless `audit_event_chain` is disabled too — the
#     append-only trigger is not the one that assigns the hash, so the forgery never reached
#     the table and the "broken" chain was in fact valid;
#   * `^- \[x\]` never matched, because the ADR writes boxes as `- [ ]`;
#   * `git ls-files -s` prints nothing for untracked paths, so the file-mode check was vacuous.
cd "$(dirname "${BASH_SOURCE[0]}")/../.." || exit 1
set -euo pipefail
trap 'echo "TRACE: aborted at line $LINENO" >&2' ERR

pass=0; fail=0
ok()  { echo "  OK    $1"; pass=$((pass+1)); }
bad() { echo "  FAIL  $1"; fail=$((fail+1)); }
chk() {
  local rc=0
  eval "$2" >/dev/null 2>&1 || rc=1
  if [ "$rc" = "0" ]; then ok "$1"; else bad "$1"; fi
  return 0
}

echo "############ A. Audit chain check detects tampering ############"
source deploy/drill/lib.sh
docker rm -f -v octo-v >/dev/null 2>&1
docker volume rm -f octo-v-v octo-v-w >/dev/null 2>&1
docker volume create octo-v-v >/dev/null
docker volume create octo-v-w >/dev/null
prepare_volumes octo-v-v octo-v-w >/dev/null
start_primary octo-v octo-v-v octo-v-w drillpw
apply_migrations octo-v >/dev/null 2>&1

run() { dexec octo-v psql -U postgres -d octo -X -q -tA -c "$1" 2>&1; }
state() { cat deploy/drill/sql/verify-audit-chain.sql | dexec octo-v psql -U postgres -d octo -X -q -tA -f - | head -1 | cut -d'|' -f1; }

for i in 1 2 3 4; do
  run "insert into octo.audit_event (occurred_at,actor,action,subject_type,subject_id,correlation_id,details)
       values (now(),'a','x','t','s$i',gen_random_uuid(),'{}'::jsonb);" >/dev/null
done
g=$(state); [ "$g" = "INTACT" ] && ok "clean chain -> INTACT" || bad "clean chain -> '$g'"

run "alter table octo.audit_event disable trigger audit_event_append_only;
     update octo.audit_event set hash=decode(repeat('cd',32),'hex') where seq=3;
     alter table octo.audit_event enable trigger audit_event_append_only;" >/dev/null
g=$(state); [ "$g" = "BROKEN" ] && ok "changed hash -> BROKEN" || bad "changed hash -> '$g'"

run "alter table octo.audit_event disable trigger audit_event_append_only;
     delete from octo.audit_event where seq=2;
     alter table octo.audit_event enable trigger audit_event_append_only;" >/dev/null
g=$(state); [ "$g" = "BROKEN" ] && ok "deleted middle row -> BROKEN" || bad "deleted middle row -> '$g'"

# Truncating a correctly-built chain down to its earliest row leaves the genuine genesis row,
# which IS valid — V6 documents that a log's newest rows being cut off cannot be detected. So
# the assertion here is that this is accepted, not rejected. The genesis anchor added to the
# verifier rejects the other shape: a surviving log whose first row is not genesis.
run "alter table octo.audit_event disable trigger audit_event_append_only;
     delete from octo.audit_event where seq > 1;
     alter table octo.audit_event enable trigger audit_event_append_only;" >/dev/null
g=$(state)
[ "$g" = "INTACT" ] && ok "tail truncated to the genesis row -> INTACT (V6's documented ceiling)" \
                    || bad "genesis-anchored single row -> '$g', expected INTACT"

# The anchor itself, isolated: keep one row, set its prev_hash to a non-genesis value AND
# recompute hash over it, so the row is internally consistent. The old fixture changed
# prev_hash without recomputing, so hash mismatch alone reported BROKEN — a regression in
# the anchor condition could never have been caught by it. With the hash honest, the only
# thing left that can flag this row is the genesis anchor.
run "alter table octo.audit_event disable trigger audit_event_append_only;
     update octo.audit_event
        set prev_hash = decode(repeat('aa',32),'hex'),
            hash = sha256(decode(repeat('aa',32),'hex')
                   || octo.audit_event_canonical(seq,occurred_at,recorded_at,actor,action,
                                                 subject_type,subject_id,correlation_id,details))
      where seq = 1;
     alter table octo.audit_event enable trigger audit_event_append_only;" >/dev/null
g=$(state)
[ "$g" = "BROKEN" ] && ok "first row not anchored at genesis (honest hash) -> BROKEN" \
                    || bad "unanchored first row -> '$g', expected BROKEN"

echo
echo "############ B. Corrected: forged hash must survive the trigger to test the mirror ############"
run "delete from octo.audit_event;" >/dev/null 2>&1 || true
run "alter table octo.audit_event disable trigger audit_event_append_only;
     alter table octo.audit_event disable trigger audit_event_chain;
     delete from octo.audit_event;" >/dev/null
run "insert into octo.audit_event (seq,occurred_at,recorded_at,actor,action,subject_type,subject_id,correlation_id,details,prev_hash,hash)
     values (1, now(), now(), 'a','x','t','s', gen_random_uuid(), '{\"k\":\"v\"}'::jsonb,
             decode(repeat('00',32),'hex'),
             sha256(decode(repeat('00',32),'hex') || convert_to('WRONG-CANONICAL','UTF8')));" >/dev/null
forged=$(run "select (hash = sha256(prev_hash || convert_to('WRONG-CANONICAL','UTF8'))) from octo.audit_event where seq=1;")
real=$(run "select (hash = sha256(prev_hash || octo.audit_event_canonical(seq,occurred_at,recorded_at,actor,action,subject_type,subject_id,correlation_id,details))) from octo.audit_event where seq=1;")
g=$(state)
[ "$forged" = "t" ] && ok "forged hash persisted (precondition actually met)" || bad "forged hash did not persist: '$forged'"
[ "$real" = "f" ]   && ok "forged row disagrees with the real canonical"        || bad "forged row matched real canonical: '$real'"
[ "$g" = "BROKEN" ] && ok "verifier rejects a forged canonical -> BROKEN"       || bad "verifier accepted forgery -> '$g'"

# Section A already provides the positive control (a clean chain reads INTACT), so no
# extra hand-built row is needed here — an earlier attempt used a NOT NULL hash placeholder
# and died on the constraint rather than testing anything.
docker rm -f -v octo-v >/dev/null 2>&1 || true
docker volume rm -f octo-v-v octo-v-w >/dev/null 2>&1 || true

echo
echo "############ C. Evidence records ############"
# The drills write timestamped files under a gitignored directory, so a fresh checkout has
# none — missing evidence is a SKIP (run the drills), never a silent pass, and never the
# fresh-checkout failure this section used to produce by demanding fixed file names.
RESTORE_EV="$(ls -t deploy/drill/evidence/restore-*.md deploy/drill/evidence/restore.md 2>/dev/null | head -1 || true)"
ROLLBACK_EV="$(ls -t deploy/drill/evidence/rollback-*.md deploy/drill/evidence/rollback.md 2>/dev/null | head -1 || true)"
if [ -n "$RESTORE_EV" ]; then
  chk "restore evidence ($RESTORE_EV) is non-empty"  "test -s '$RESTORE_EV'"
  chk "restore record has no FAIL row"                "test -s '$RESTORE_EV' && ! grep -q '| FAIL' '$RESTORE_EV'"
  chk "restore record measures RPO and RTO"           "grep -q '| RPO |' '$RESTORE_EV' && grep -q '| RTO |' '$RESTORE_EV'"
  chk "restore record states local != staging"        "grep -qi 'not staging evidence' '$RESTORE_EV'"
else
  echo "  SKIP  no restore evidence — run deploy/drill/restore-drill.sh to produce one"
fi
if [ -n "$ROLLBACK_EV" ]; then
  chk "rollback evidence ($ROLLBACK_EV) is non-empty" "test -s '$ROLLBACK_EV'"
  chk "rollback record has no FAIL row"               "test -s '$ROLLBACK_EV' && ! grep -q '| FAIL' '$ROLLBACK_EV'"
else
  echo "  SKIP  no rollback evidence — run deploy/drill/rollback-rehearsal.sh to produce one"
fi

echo
echo "############ D. Corrected: no ADR-0002 acceptance box ticked ############"
ticked=$(grep -c -- '- \[x\]' docs/adr/0002-self-hosted-supabase.md || true)
ticked=${ticked:-0}
[ "$ticked" = "0" ] && ok "no box ticked (found $ticked)" || bad "$ticked box(es) ticked — must be 0"
chk "all 13 acceptance boxes present and open" "test \$(grep -c -- '- \[ \]' docs/adr/0002-self-hosted-supabase.md) -eq 13"
chk "ADR says boxes need staging evidence"     "grep -q 'not\*\* satisfied by a document' docs/adr/0002-self-hosted-supabase.md"

echo
echo "############ E. Corrected: line endings, exec bits, ignore rule ############"
for f in deploy/drill/lib.sh deploy/drill/restore-drill.sh deploy/drill/rollback-rehearsal.sh \
         deploy/drill/sql/verify-audit-chain.sql deploy/drill/sql/post-restore-check.sql \
         deploy/drill/README.md docs/upgrade-runbook.md docs/incident-runbook.md; do
  if [ "$(tr -cd '\r' < "$f" | wc -c)" = "0" ]; then ok "LF only: $f"; else bad "CR in $f"; fi
done
if [ -x deploy/drill/restore-drill.sh ]; then ok "restore-drill.sh is executable on disk"; else bad "restore-drill.sh not executable"; fi
if [ -x deploy/drill/rollback-rehearsal.sh ]; then ok "rollback-rehearsal.sh is executable on disk"; else bad "rollback-rehearsal.sh not executable"; fi
chk "evidence dir is gitignored" "git check-ignore -q deploy/drill/evidence/restore.md"

echo
echo "############ F. No dangling references ############"
# Resolve each link relative to the directory of the file that contains it. The first
# attempt searched only from the repo root, so a legitimate sibling link such as
# `0001-platform-architecture.md` written inside docs/adr/ looked broken when it is not.
missing=0
for src in docs/incident-runbook.md docs/upgrade-runbook.md docs/capacity-test-plan.md \
           docs/drill-evidence-template.md docs/restore-runbook.md \
           docs/adr/0002-self-hosted-supabase.md deploy/drill/README.md; do
  srcdir="$(dirname "$src")"
  # `|| true` because grep exits 1 on no match, which set -e would treat as fatal.
  refs="$( { grep -ohE '\]\([^)]*\.(md|sh|sql)\)' "$src" 2>/dev/null \
            | sed -E 's/^\]\(//; s/\)$//' | grep -vE '^https?://' | sort -u; } || true)"
  while IFS= read -r ref; do
    [ -n "$ref" ] || continue
    case "$ref" in /*) cand="$ref" ;; *) cand="$srcdir/$ref" ;; esac
    if [ ! -e "$cand" ] && [ ! -e "$ref" ]; then
      echo "    missing: $ref   (from $src)"; missing=$((missing+1))
    fi
  done <<<"$refs"
done
[ "$missing" = "0" ] && ok "every referenced path resolves from its source file" || bad "$missing dangling reference(s)"

echo
echo "############ G. Backing code claims against source ############"
chk "V6 defines the audit chain trigger"      "grep -q 'create trigger audit_event_chain' db/migrations/V6__audit_event.sql"
chk "V6 defines audit_event_canonical"        "grep -q 'create function mesta.audit_event_canonical' db/migrations/V6__audit_event.sql"
chk "V1 defines ledger append-only trigger"   "grep -q 'create trigger ledger_event_append_only' db/migrations/V1__init.sql"
chk "Flyway placeholder is runtime_role"      "grep -q 'runtime_role' modules/api/src/main/resources/application.yml"
# Contiguity, not a hard-coded count: extract every version, and the sorted unique list
# must equal seq 1..top. A file-count check stays green forever and notices nothing.
migrations_contiguous() {
  local v top
  v="$(git ls-files db/migrations | sed -nE 's#.*/V([0-9]+)__[^/]*\.sql$#\1#p' | sort -n | uniq)"
  [ -n "$v" ] || return 1
  top="$(printf '%s\n' "$v" | tail -1)"
  [ "$v" = "$(seq 1 "$top")" ]
}
chk "migrations are contiguous V1..latest" "migrations_contiguous"
chk "README documents forward-only migrations" "grep -q 'forward-only' deploy/README.md"

echo
echo "================================================================"
echo "  SELFTEST: $pass passed, $fail failed"
echo "================================================================"
exit $(( fail > 0 ? 1 : 0 ))
