# deploy/loadtest — capacity load-test harness

Closes the "load-test tooling does not exist in this repo yet" gap `docs/capacity-test-plan.md`
§6 recorded — the tooling half of #307's capacity/dependency-failure acceptance box.

## What this is for

`docs/capacity-test-plan.md` §2 defines seven capacity cases (C1-C7) and leaves choosing a
tool as part of the work. `capacity-drill.sh` drives **C1** (sustained reads — 99% of GET
under 500ms) and a C5-style concurrency ramp, using `ab` (`apache2-utils`) — already present
on most Linux hosts and CI images, so this adds no new binary to install, pin, or scan.

## What it proves, and what it does not

| It proves | It cannot prove |
| --- | --- |
| The targeted paths clear the stated p99-latency and failure-rate budget at the given concurrency levels | Staging's real traffic shape — run it there before trusting the number |
| A concurrency ramp (C5) doesn't regress latency as load increases | C2-C4/C6-C7: writes, ingestion throughput, WAL/bloat growth — `ab` only drives GETs |
| — | Any of the dependency-failure cases (D1-D7, §3) — those are observed by hand while a dependency is stopped, not scripted |

**A local run is preparation, never acceptance.** ADR-0002's acceptance box is ticked only
from a filled [drill evidence record](../../docs/drill-evidence-template.md) produced on
staging, same contract as `deploy/drill/`.

`ab` does not check HTTP status codes — see the script's header comment. Verify each target
path returns the expected status with `curl -o /dev/null -w '%{http_code}\n'` before trusting
a PASS.

## Running

```bash
# Defaults: localhost:8080, the two public actuator paths, 500 requests, concurrency 1/10/50.
deploy/loadtest/capacity-drill.sh

# Against a real target with auth, custom paths and concurrency:
BASE_URL=https://api-octo.mesta.click \
TOKEN=<bearer token> \
PATHS="/actuator/health /api/v1/agent-runs?tenantId=<id>" \
CONCURRENCY_LEVELS="1 10 50 100" \
REQUESTS=1000 \
  deploy/loadtest/capacity-drill.sh
```

Writes `deploy/loadtest/evidence/capacity-<utc>.md` (or the path given as `$1`) and exits
non-zero if any path/concurrency combination misses the threshold — so it can gate a shell
chain or CI job the same way `deploy/drill/*.sh` does.

## Files

| File | Role |
| --- | --- |
| `capacity-drill.sh` | Runs `ab` across the configured paths × concurrency levels, grades each against C1's threshold, writes the evidence record |
