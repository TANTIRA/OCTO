#!/usr/bin/env bash
# Shared plumbing for the local rehearsal harness (ADR-0002 acceptance, issue #307).
#
# This harness exists so the procedures in docs/restore-runbook.md and
# docs/upgrade-runbook.md can be *executed* before they are needed for real. It runs
# throwaway PostgreSQL containers on the local Docker daemon.
#
# What it can prove: the migration chain applies cleanly, a base backup plus WAL stream
# restores to a chosen point in time, the post-restore verification returns clean, and
# RPO/RTO can be measured by the same arithmetic used on staging.
#
# What it cannot prove: staging timings, real data volume, the deployed Supabase topology,
# or that backups exist in production. A local run is preparation, never acceptance —
# ADR-0002's boxes are ticked from a filled evidence record on staging (docs/drill-evidence-template.md).
set -euo pipefail

PG_IMAGE="${PG_IMAGE:-postgres:17-alpine}"
PG_USER="${PG_USER:-postgres}"
PG_DB="${PG_DB:-octo}"
RUNTIME_ROLE="${RUNTIME_ROLE:-octo_app}"

# Every path this harness passes to Docker is a path *inside* a Linux container. Git Bash
# on Windows rewrites arguments that look like absolute paths before they reach docker.exe,
# so `/shared/primary` arrives as `C:/Program Files/Git/shared/primary` and the cluster
# initialises outside the volume. Nothing here passes a host path, so disabling the
# conversion is safe — and it is a no-op on Linux, where the harness also runs.
case "$(uname -s)" in
  MINGW* | MSYS* | CYGWIN*) export MSYS_NO_PATHCONV=1 ;;
esac

# Flyway substitutes ${runtime_role} in the migrations; the default in application.yml is
# ${DB_USER:octo}. Keep RUNTIME_ROLE in step with the role the app actually connects as.
MIGRATION_DIR="${MIGRATION_DIR:-db/migrations}"

log()  { printf '[%s] %s\n' "$(date -u +%H:%M:%S)" "$*"; }
warn() { printf '[%s] WARN %s\n' "$(date -u +%H:%M:%S)" "$*" >&2; }
die()  { printf '[%s] FAIL %s\n' "$(date -u +%H:%M:%S)" "$*" >&2; exit 1; }

# Epoch milliseconds. `date +%s%3N` is a GNU extension: BSD/macOS date prints the
# %3N literally ("…3N"), which then fails inside $(( )) with "value too great
# for base" and kills the drill mid-run. Prefer bash's $EPOCHREALTIME (5.0+),
# fall back to perl (core Time::HiRes) for bash 4.
now_ms() {
  local t="${EPOCHREALTIME:-}"
  if [ -n "$t" ]; then
    # <sec>.<6-digit usec>; 10# stops a leading-zero fraction reading as octal
    printf '%d\n' "$(( ${t%.*} * 1000 + 10#${t#*.} / 1000 ))"
  else
    perl -MTime::HiRes=time -e 'printf "%d\n", time() * 1000'
  fi
}

require_docker() {
  command -v docker >/dev/null 2>&1 || die "docker not found on PATH"
  docker info >/dev/null 2>&1 || die "docker daemon is not reachable — start Docker and retry"
}

# Repository root, so the harness works from any working directory.
repo_root() {
  local d
  d="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  printf '%s' "$(cd "$d/../.." && pwd)"
}

# docker exec on a container, stdin forwarded. -i is required for piped SQL.
dexec() {
  local c="$1"; shift
  docker exec -i "$c" "$@"
}

# psql inside a container. Usage: psql_c <container> <db> [extra psql args...]
psql_c() {
  local c="$1" db="$2"; shift 2
  dexec "$c" psql -U "$PG_USER" -d "$db" -X -q -v ON_ERROR_STOP=1 "$@"
}

# Scalar query. Usage: sql_scalar <container> <db> <sql>
sql_scalar() {
  local c="$1" db="$2" sql="$3"
  psql_c "$c" "$db" -tAc "$sql"
}

wait_ready() {
  local c="$1" tries="${2:-60}" i
  for ((i = 1; i <= tries; i++)); do
    if docker exec "$c" pg_isready -U "$PG_USER" -q >/dev/null 2>&1; then return 0; fi
    sleep 0.5
  done
  return 1
}

# Wait until a freshly started cluster accepts connections *and* has left recovery.
wait_accepting() {
  local c="$1" db="${2:-postgres}" tries="${3:-120}" i st
  for ((i = 1; i <= tries; i++)); do
    st="$(docker exec "$c" psql -U "$PG_USER" -d "$db" -tAc \
      "select case when pg_is_in_recovery() then 'recovery' else 'ready' end" 2>/dev/null || true)"
    [[ "$st" == "ready" ]] && return 0
    sleep 0.5
  done
  return 1
}

cleanup_container() {
  local c="$1"
  # -v: the postgres image declares VOLUME /var/lib/postgresql/data, so every container
  # also creates an anonymous volume alongside the named ones; without -v it leaks.
  docker rm -f -v "$c" >/dev/null 2>&1 || true
}

cleanup_volume() {
  local v="$1"
  docker volume rm -f "$v" >/dev/null 2>&1 || true
}

# Mount-point directories must exist and be writable by the postgres user *before* the
# cluster starts: archive_command runs as postgres and fails the whole archive if the
# target directory is missing (PostgreSQL does not create it).
prepare_volumes() {
  local shared_vol="$1" archive_vol="$2"
  docker run --rm -u 0:0 \
    -v "${shared_vol}:/shared" \
    -v "${archive_vol}:/archive" \
    "$PG_IMAGE" \
    sh -c 'mkdir -p /shared /archive/wal && chown -R postgres:postgres /shared /archive && chmod 700 /archive/wal' >/dev/null
}

# The primary cluster: WAL archiving on, so a point-in-time restore is possible.
# POSTGRES_INITDB_ARGS turns on data checksums, which production storage should also have.
start_primary() {
  local name="$1" shared_vol="$2" archive_vol="$3" password="$4"
  cleanup_container "$name"
  docker run -d --name "$name" \
    -e POSTGRES_PASSWORD="$password" \
    -e POSTGRES_DB="$PG_DB" \
    -e PGDATA=/shared/primary \
    -e POSTGRES_INITDB_ARGS=--data-checksums \
    -v "${shared_vol}:/shared" \
    -v "${archive_vol}:/archive" \
    "$PG_IMAGE" \
    -c "archive_mode=on" \
    -c "archive_command=test ! -f /archive/wal/%f && cp %p /archive/wal/%f" \
    -c "archive_timeout=30s" \
    -c "wal_level=replica" >/dev/null
  wait_ready "$name" 120 || die "primary $name did not become ready"
  # initdb runs an internal start/stop cycle; settle until the server answers inside a
  # query, not merely pg_isready during that cycle.
  wait_accepting "$name" "$PG_DB" 120 || die "primary $name did not accept queries"
}

# Start a second container against an existing cluster directory (restore target).
start_data_container() {
  local name="$1" shared_vol="$2" archive_vol="$3" datadir="$4"
  shift 4
  cleanup_container "$name"
  docker run -d --name "$name" \
    -e PGDATA="$datadir" \
    -v "${shared_vol}:/shared" \
    -v "${archive_vol}:/archive" \
    "$PG_IMAGE" "$@" >/dev/null
}

# Flyway's placeholder substitution, applied to every migration before execution.
substitute_placeholders() {
  sed "s/\${runtime_role}/${RUNTIME_ROLE}/g"
}

# Migration filenames on disk, in version order (sort -V puts V2 before V10).
migration_files() {
  local root; root="$(repo_root)"
  (cd "$root" && git ls-files "$MIGRATION_DIR") \
    | grep -E '/V[0-9]+__[^/]*\.sql$' \
    | sed 's#^.*/##' \
    | sort -V
}

# Highest migration version on disk.
migration_top() {
  local name ver
  name="$(migration_files | tail -1)"
  [[ -n "$name" ]] || return 1
  ver="${name#V}"; ver="${ver%%__*}"
  printf '%s' "$((10#$ver))"
}

# The lowest version the rehearsal helpers can run against: the migration that adds
# ledger_event.tenant_id, which every seeded ledger row must stamp (and which only exists
# after the mesta -> octo schema move). Derived from the migration files rather than
# pinned, so the floor follows the schema instead of drifting stale.
rehearsal_floor() {
  local f ver
  while IFS= read -r f; do
    if grep -qE 'alter table[[:space:]]+(octo|mesta)\.ledger_event' "$(repo_root)/${MIGRATION_DIR}/${f}" \
       && grep -qE 'add column[[:space:]]+tenant_id' "$(repo_root)/${MIGRATION_DIR}/${f}"; then
      ver="${f#V}"; ver="${ver%%__*}"
      printf '%s' "$((10#$ver))"
      return 0
    fi
  done < <(migration_files)
  return 1
}

# Rehearsal-window guard (#302): a V(n-1) -> V(n) rehearsal is only meaningful inside the
# range the helpers can actually build. Below the floor the seed inserts have no
# ledger_event.tenant_id to stamp; above the newest file the drill would apply everything
# yet label the evidence with a version that does not exist; and TO must be strictly
# greater than FROM or the second apply_migrations call has nothing to do.
validate_rehearsal_window() {
  local from="$1" to="$2" floor top
  [[ "$from" =~ ^[0-9]+$ ]] || die "FROM_VERSION must be a migration number, got '${from}'"
  [[ "$to" =~ ^[0-9]+$ ]]   || die "TO_VERSION must be a migration number, got '${to}'"
  floor="$(rehearsal_floor)" || die "could not derive the rehearsal floor from $MIGRATION_DIR"
  top="$(migration_top)" || die "no migrations found under $MIGRATION_DIR"
  (( 10#$from >= floor )) \
    || die "FROM_VERSION=V${from} is below the rehearsal floor V${floor} — the helpers need ledger_event.tenant_id, added there"
  (( 10#$to > 10#$from )) \
    || die "TO_VERSION (V${to}) must be greater than FROM_VERSION (V${from})"
  (( 10#$to <= top )) \
    || die "TO_VERSION=V${to} exceeds the newest migration on disk (V${top})"
}

# Empty the audit log. Only safe while both triggers are off: audit_event.seq is a plain bigint
# assigned by audit_event_chain (V6) — not an identity column, as the sibling
# tenant_member_event is (V8) — so there is no sequence to restart, and a cleared table cannot
# be re-seeded by hand without also synthesising a valid genesis hash. Callers that need a
# chain again should re-seed through the trigger, which is the only writer that can build one.
# Both triggers must be off to touch rows directly: audit_event_chain owns seq, prev_hash and
# hash, so it overwrites whatever a caller writes while it is enabled.
reset_audit_chain() {
  local c="$1"
  psql_c "$c" "$PG_DB" -c "
    alter table octo.audit_event disable trigger audit_event_append_only;
    alter table octo.audit_event disable trigger audit_event_chain;
    delete from octo.audit_event;" >/dev/null
}

# Hand the audit log back to the trigger, ready for normal writes.
enable_audit_chain() {
  local c="$1"
  psql_c "$c" "$PG_DB" -c "
    alter table octo.audit_event enable trigger audit_event_chain;
    alter table octo.audit_event enable trigger audit_event_append_only;" >/dev/null
}

# Apply migrations V1..V<stop_at> in version order, recording flyway_schema_history the way
# Flyway would. stop_at defaults to the highest version present.
apply_migrations() {
  local c="$1" stop_at="${2:-}" matched=0
  local root; root="$(repo_root)"
  local files
  # Version order, not lexicographic: sort -V puts V2 before V10.
  mapfile -t files < <(migration_files)

  # Flyway's history table lives in the domain schema. On a fresh database Flyway creates
  # `octo` for it first (application.yml `flyway.schemas: octo`), V1-V27 then populate
  # mesta.*, and V28 moves every object into octo and drops the now-empty mesta. V28's
  # `create schema if not exists octo` is a no-op here, which is exactly why creating it up
  # front is the faithful path. A deployed database instead had ops run
  # `alter schema mesta rename to octo` before the boot, so V28 is a no-op there too.
  # Both paths converge on the same end state.
  psql_c "$c" "$PG_DB" -c "
    do \$\$ begin
      if not exists (select 1 from pg_roles where rolname = '${RUNTIME_ROLE}') then
        execute 'create role ${RUNTIME_ROLE} login password ''runtime''';
      end if;
    end \$\$;" >/dev/null
  psql_c "$c" "$PG_DB" -c "create schema if not exists octo;" >/dev/null
  psql_c "$c" "$PG_DB" -c "
    create table if not exists octo.flyway_schema_history (
      installed_rank int primary key,
      version varchar(50), description varchar(200) not null, type varchar(20) not null,
      script varchar(1000) not null, checksum int, installed_by varchar(100) not null,
      installed_on timestamp not null default now(), execution_time int not null,
      success boolean not null);" >/dev/null

  # Ranking continues across calls, so an upgrade rehearsal can migrate to V39 and then V40
  # in the same database without colliding on installed_rank.
  local rank applied_through
  rank="$(sql_scalar "$c" "$PG_DB" "select coalesce(max(installed_rank), 0) from octo.flyway_schema_history")"
  applied_through="$(sql_scalar "$c" "$PG_DB" "select coalesce(max(version::int), 0) from octo.flyway_schema_history")"

  # stop_at must name a real, not-yet-applied version (#302): without these guards a
  # TO_VERSION beyond the newest file applied everything but labelled the evidence with
  # a version that does not exist, and a TO_VERSION equal to the applied head died later
  # and less clearly on "no migrations found".
  if [[ -n "$stop_at" ]]; then
    local top
    top="$(migration_top)" || die "no migrations found under $MIGRATION_DIR"
    [[ "$stop_at" =~ ^[0-9]+$ ]] || die "stop_at must be a migration number, got '${stop_at}'"
    (( 10#$stop_at <= 10#$top )) \
      || die "requested V${stop_at} but the newest migration on disk is V${top}"
    (( 10#$stop_at > 10#$applied_through )) \
      || die "requested V${stop_at} but ${PG_DB} is already at V${applied_through}"
  fi

  local f name ver desc
  for f in "${files[@]}"; do
    name="$(basename "$f")"
    ver="${name#V}"; ver="${ver%%__*}"
    [[ -n "$stop_at" ]] && (( 10#$ver > 10#$stop_at )) && continue
    # Already applied on an earlier call: Flyway skips it, and so must the rehearsal,
    # otherwise the second call replays V1..V(n-1) and V28 collides with its own output.
    (( 10#$ver <= 10#$applied_through )) && continue
    desc="${name#*__}"; desc="${desc%.sql}"

    if ! substitute_placeholders < "${root}/${MIGRATION_DIR}/${name}" | dexec "$c" \
        psql -U "$PG_USER" -d "$PG_DB" -X -q -v ON_ERROR_STOP=1 -f - >/dev/null; then
      die "migration $name failed to apply"
    fi
    rank=$((rank + 1))
    psql_c "$c" "$PG_DB" -c "
      insert into octo.flyway_schema_history
        (installed_rank, version, description, type, script, installed_by, execution_time, success)
      values (${rank}, '${ver}', '${desc}', 'SQL', '${name}', 'octo_migrate', 1, true);" >/dev/null
    matched=$((matched + 1))
  done

  [[ "$matched" -gt 0 ]] || die "no migrations found under $MIGRATION_DIR"
  if (( rank > 10#$applied_through )); then
    log "applied $(( rank - 10#$applied_through )) new migration(s) (up to V${stop_at:-latest}) as role ${RUNTIME_ROLE}"
  else
    log "no new migrations to apply (already at V${applied_through}, requested up to V${stop_at:-latest})"
  fi
}

# Emit a machine-readable evidence record. Ids and timings only, never data.
# Usage: evidence_header <out.md> <kind> <environment> <body-file>
evidence_header() {
  local out="$1" kind="$2" env="$3" body="${4:-/dev/null}" sha
  sha="$(cd "$(repo_root)" && git rev-parse --short HEAD 2>/dev/null || echo unknown)"
  {
    cat <<EOF
# Drill evidence — ${kind}

- Drill id: $(date -u +%Y%m%dT%H%M%SZ)-${kind}
- Environment: ${env}
- Started (UTC): $(date -u +%Y-%m-%dT%H:%M:%SZ)
- Operator: $(whoami)
- Repo commit: ${sha}
- Postgres image: ${PG_IMAGE}
- Runtime role / migration placeholder: ${RUNTIME_ROLE}

EOF
    cat "$body"
  } > "$out"
}
