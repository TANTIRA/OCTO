# Staging provisioning runbook

How to stand up an OCTO staging environment. Owner: Platform / DevOps
(`#301` arranges the recovery environment; [drill-evidence-template.md](../drill-evidence-template.md)
defines what the environment must satisfy).

**Why this exists.** ADR-0002's acceptance criteria are graded on dated evidence from a
rehearsal **on staging**, and a local harness run does not tick a box
([0002-self-hosted-supabase.md](../adr/0002-self-hosted-supabase.md), "Production acceptance
criteria"). No staging environment exists: every deployed surface is production. Five of
ADR-0002's thirteen criteria, one at ADR-0007, and #301/#302 all wait on this.

## 0. Decide and record these first

| Decision | Default | Note |
| --- | --- | --- |
| Topology | **Separate Dokploy project on the existing host** | Gives the six separations ADR-0002 "Environment topology" asks for: databases, credentials, keys, storage, networks, backups. Record as a known limitation that it is **not** a separate failure domain; a second VPS is the upgrade path |
| Public hostnames | None initially | The drills run on the host and need no ingress. Domains can come later |
| Source of truth for images | The same pinned release as production | Record tag **and** digest per image in the drill record |

Write the decisions into the staging project description, the way the Supabase version is
recorded today ([infra/supabase/README.md](../../infra/supabase/README.md)).

## 1. Supabase stack

The largest piece, and the one every other criterion depends on.

1. Create the staging project in Dokploy, and deploy the **Supabase template** into it,
   the same way production runs it ([infra/supabase/README.md](../../infra/supabase/README.md),
   "Deployed vs vendored").
2. **Generate fresh secrets. Never reuse production values** (ADR-0002 "Environment
   topology": separate credentials and keys).

   ```bash
   infra/supabase/vendor/utils/generate-keys.sh
   infra/supabase/vendor/utils/add-new-auth-keys.sh
   infra/supabase/vendor/utils/db-passwd.sh
   ```

3. Set the stack env from the vendored contract
   ([.env.example](../../infra/supabase/vendor/.env.example)). The values a fresh stack
   cannot start without:

   | Key | Note |
   | --- | --- |
   | `POSTGRES_PASSWORD` | fresh |
   | `JWT_SECRET`, `JWT_EXPIRY` | fresh; `JWT_EXPIRY=3600` is the vendored default |
   | `ANON_KEY`, `SERVICE_ROLE_KEY` | fresh, signed by the new `JWT_SECRET` |
   | `ANON_KEY_ASYMMETRIC`, `SERVICE_ROLE_KEY_ASYMMETRIC` | fresh, if the release uses ES256 |
   | `DASHBOARD_USERNAME`, `DASHBOARD_PASSWORD` | Studio basic auth; private network only |
   | `SECRET_KEY_BASE`, `VAULT_ENC_KEY` | fresh |
   | `API_EXTERNAL_URL`, `SUPABASE_PUBLIC_URL`, `SITE_URL` | staging URLs |
   | `POOLER_TENANT_ID` | fresh |
   | `DISABLE_SIGNUP`, `ENABLE_EMAIL_SIGNUP` | staging policy; production is invite-only |
   | `SOLANA_ENABLED` | only if SIWS is rehearsed here |

4. Record the release tag and every image digest.

## 2. Base backup and WAL archiving

**Do this before the drills.** It is the precondition the evidence template checks
(§2), and it is the identical work production still needs: the deployed database has
`archive_mode = off` and `pg_stat_archiver.archived_count = 0` since 26 Sept. Whatever
is configured here is the rehearsed version of that fix.

1. Choose a destination that is **off-host and deletion-protected**: object storage, not
   a local volume. Encryption keys live in the secret manager, not on the DB host.
2. Configure the base backup. The pinned `supabase/postgres` image ships
   `pg_basebackup` but **no `pgBackRest` and no `wal-g`** (#301) — under the
   option-A staging approach this is a scheduled `pg_basebackup` written to a
   mounted staging volume, with the host-side job below shipping both base
   backup and WAL off-host. Record the "container archives locally, host ships
   off-host" split as a deviation if ADR-0002's deletion-protected destination
   cannot be arranged directly. Schedule the base backup daily.
   ([restore-runbook.md](../restore-runbook.md) §2 lists the open A/B/C mechanism
   choice — what is configured here is the rehearsal for whichever lands.)
3. Configure archiving on the Postgres host. Under option A, `archive_command`
   copies each segment into the mounted volume; the host-side job ships it to
   object storage:

   ```
   archive_mode    = on
   archive_command = 'test ! -f /wal-archive/%f && cp %p /wal-archive/%f'
   archive_timeout = 60s                      # this bounds RPO -- see below
   ```

   Options B (`wal-g wal-push %p`) and C (`pg_receivewal` sidecar) replace the
   `archive_command` line; the rest of this section is unchanged.

   `archive_timeout` is the RPO floor: a segment is only archived when it fills or the
   timeout fires. Set it comfortably inside the `<= 15 min` target so the measured RPO has
   margin, and record the value you chose.
4. Verify. This is the acceptance evidence, not the config:

   ```sql
   show archive_mode;                          -- on
   show archive_command;                       -- not (disabled)
   show archive_timeout;
   select archived_count, last_archived_time, failed_count, last_failed_time
     from pg_stat_archiver;
   ```

   Success is `archived_count > 0` with a recent `last_archived_time`, and a base backup
   whose **last success** was checked, not merely scheduled.

## 3. Database roles

Run once per environment as superuser, before the first api boot
([init-db-roles.sql](../../infra/init-db-roles.sql)):

```bash
psql -h <staging-db-host> -U postgres -f infra/init-db-roles.sql
```

The file ships placeholder passwords. Generate strong values, substitute at run time,
never commit the result. The names must match `DB_USER` / `DB_MIGRATION_USER` exactly: the
V3+ migrations grant to `"${runtime_role}"`, which Flyway resolves from `DB_USER`.

## 4. App stack

Deploy [dokploy.compose.yml](../../deploy/dokploy.compose.yml) into the staging project.

- The three image tags are **required**: the compose refuses to render without
  `API_IMAGE_TAG`, `AGENTS_IMAGE_TAG`, `WEB_IMAGE_TAG`. Use the commit sha under test.
- Set the env from [infra/.env.example](../../infra/.env.example), the names-only contract.
  The staging-specific ones: `ENVIRONMENT`, `SPRING_PROFILES_ACTIVE`, `DATA_NETWORK`,
  `AUTH_ISSUER`, `AUTH_JWKS_URL`, `API_PUBLIC_URL`, `WEB_PUBLIC_URL`.
- `DB_HOST` / `DB_NAME` point at the staging database. `DB_PORT` is hardcoded `5432`.
- Leave vendor keys (`HELIUS_*`, `ARBITRUM_*`, `ALPHA_VANTAGE_*`, `OPENROUTER_*`) empty
  unless a rehearsal needs them: Spring's `@ConditionalOnProperty` treats a present-but-empty
  value as configured, and the api crash-loops.
- `OCTO_PLATFORM_ADMINS` needs at least one subject to provision the smoke tenant.

## 5. Neo4j

Stand up Neo4j community, pinned the same way production pins it. Bolt stays private to the
Docker network; no public domain. Then set `NEO4J_URI`, `NEO4J_DATABASE`, `NEO4J_USER`,
`NEO4J_PASSWORD` on the api. Blank `NEO4J_URI` leaves the graph projector off, which is fine
if the rehearsal does not need it.

## 6. Smoke tenant and user

The evidence template requires `octo-ops-smoke@test.invalid` for post-restore verification
([restore-runbook.md](../restore-runbook.md) §3). Create it through the staging auth, not by
copying a production row.

## 7. Rehearse, and file the record

On the staging host, with the repo checked out at the commit under test:

```bash
deploy/drill/restore-drill.sh
deploy/drill/rollback-rehearsal.sh
deploy/drill/selftest.sh
```

Then file each result per [drill-evidence-template.md](../drill-evidence-template.md). Two
rules the template enforces:

- `Environment` must read `staging`; a local run does not tick a box.
- **The reviewer must not be the operator** (§6).

## 8. Open questions to settle before or during

1. **Does a drill host satisfy the criterion, or must the drill exercise staging's real
   backup?** [restore-drill.sh](../../deploy/drill/restore-drill.sh) always provisions its own
   throwaway containers (`start_primary` runs `docker run`; the only overrides are `PG_IMAGE`,
   `PG_USER`, `PG_DB`, `RUNTIME_ROLE`, `MIGRATION_DIR`). So a staging run rehearses the
   *procedure on staging-class infrastructure*; it does not restore staging's own backup.
   Under the stricter reading, the harness needs a mode that targets an existing database,
   which is new work.
2. **Who controls DNS for `mesta.click`?** Not recorded anywhere in the repo. Only needed
   when staging gets public hostnames.
3. **Which commit does staging deploy?** Production builds from the
   `daemon-blockint-tech/OCTO` mirror at `chore/octo-deploy`, because Dokploy's GitHub App
   cannot see `TANTIRA`. A staging environment wired the same way tests the mirror, not
   `main`, which matters for a migration rehearsal.

## 9. What this does not cover

Capacity and dependency-failure drills ([capacity-test-plan.md](../capacity-test-plan.md)),
the load harness ([deploy/loadtest/](../../deploy/loadtest/)), and RPO/RTO owner sign-off,
which ADR-0002 makes launch-blocking.
