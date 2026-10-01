#!/usr/bin/env bash
# Capacity load-test harness (ADR-0002 acceptance, issue #307).
#
# docs/capacity-test-plan.md §6 recorded that no load-test tooling existed in this repo.
# This closes that gap for case C1 (sustained reads) and a C5-style concurrency ramp,
# using `ab` (apache2-utils) — already present on most Linux hosts/CI images, so this adds
# no new binary dependency to pin or scan.
#
# What it proves: the targeted paths serve the requested concurrency with the stated
# failure and p99-latency budget, against whatever BASE_URL points at.
# What it cannot prove: staging's real traffic shape, C2-C4/C6-C7 (writes, ingestion,
# WAL/bloat growth — ab only drives GETs), or any of the dependency-failure cases (D1-D7
# in capacity-test-plan.md §3), which are observed by hand while a dependency is stopped,
# not scripted here. A local run against a dev instance is preparation, never acceptance —
# docs/drill-evidence-template.md's record, filled on staging, is what ticks the ADR-0002 box.
#
# `ab` does not check HTTP status codes — "Failed requests" only counts connection-level
# failures and (without -l) response-length mismatches. A path that 404s or 500s on every
# request can still report zero failures here. Verify each path returns the expected status
# with a plain `curl -o /dev/null -w '%{http_code}\n'` before trusting a PASS from this script.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
TOKEN="${TOKEN:-}"
REQUESTS="${REQUESTS:-500}"
CONCURRENCY_LEVELS="${CONCURRENCY_LEVELS:-1 10 50}"
PATHS="${PATHS:-/actuator/health /actuator/health/readiness}"
THRESHOLD_MS="${THRESHOLD_MS:-500}"
MAX_FAILED="${MAX_FAILED:-0}"
EVIDENCE="${1:-deploy/loadtest/evidence/capacity-$(date -u +%Y%m%dT%H%M%SZ).md}"

log()  { printf '[%s] %s\n' "$(date -u +%H:%M:%S)" "$*"; }
warn() { printf '[%s] WARN %s\n' "$(date -u +%H:%M:%S)" "$*" >&2; }
die()  { printf '[%s] FAIL %s\n' "$(date -u +%H:%M:%S)" "$*" >&2; exit 1; }

command -v ab >/dev/null 2>&1 || die "ab (apache2-utils) not found on PATH — install it (apt: apache2-utils) and retry"

mkdir -p "$(dirname "$EVIDENCE")"
sha="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"

{
  cat <<EOF
# Capacity drill evidence

- Drill id: $(date -u +%Y%m%dT%H%M%SZ)-capacity
- Target: ${BASE_URL}
- Started (UTC): $(date -u +%Y-%m-%dT%H:%M:%SZ)
- Operator: $(whoami)
- Repo commit: ${sha}
- Requests per run: ${REQUESTS}
- Concurrency levels: ${CONCURRENCY_LEVELS}
- Threshold: 99th percentile < ${THRESHOLD_MS}ms, failed requests <= ${MAX_FAILED} (C1, docs/capacity-test-plan.md §2)

| Path | Concurrency | p99 (ms) | Failed | RPS | Pass/Fail |
| --- | --- | --- | --- | --- | --- |
EOF
} > "$EVIDENCE"

overall_pass=0

for path in $PATHS; do
  for c in $CONCURRENCY_LEVELS; do
    auth_args=()
    [ -n "$TOKEN" ] && auth_args=(-H "Authorization: Bearer ${TOKEN}")

    # -l: don't flag a dynamic JSON response as a "failure" just because its length varies
    # between requests — the one failure mode ab does detect on its own.
    out="$(ab -q -l -n "$REQUESTS" -c "$c" "${auth_args[@]}" "${BASE_URL}${path}" 2>&1 || true)"

    failed="$(printf '%s\n' "$out" | awk '/^Failed requests:/ {print $NF}')"
    rps="$(printf '%s\n' "$out" | awk '/^Requests per second:/ {print $4}')"
    p99="$(printf '%s\n' "$out" | awk '/^ *99%/ {print $2}')"

    failed="${failed:-NA}"
    rps="${rps:-NA}"
    p99="${p99:-NA}"

    status="FAIL"
    if [ "$failed" != "NA" ] && [ "$p99" != "NA" ] \
      && [ "$failed" -le "$MAX_FAILED" ] 2>/dev/null \
      && [ "$p99" -lt "$THRESHOLD_MS" ] 2>/dev/null; then
      status="PASS"
    else
      overall_pass=1
      warn "${path} @ c=${c}: p99=${p99}ms failed=${failed} — did not clear the threshold"
    fi

    log "${path} @ c=${c}: p99=${p99}ms rps=${rps} failed=${failed} -> ${status}"
    printf '| %s | %s | %s | %s | %s | %s |\n' "$path" "$c" "$p99" "$failed" "$rps" "$status" >> "$EVIDENCE"
  done
done

log "evidence written to ${EVIDENCE}"
[ "$overall_pass" -eq 0 ] || die "one or more cases missed the C1 threshold — see ${EVIDENCE}"
