# infra/supabase — vendored Supabase distribution

Do not hand-maintain a Supabase compose file. Vendor the official pinned release and layer `docker-compose.override.yml` on top.

## Setup

`vendor/` is the official `docker/` directory vendored at `self-hosted/v0.8.1` (commit `8c7a4d9`, recorded in `vendor/.supabase-version`). Refresh with a new tag + diff review + staging rehearsal:

```bash
git clone --depth 1 --branch self-hosted/vX.Y.Z https://github.com/supabase/supabase /tmp/supabase
```

then either copy `docker/` over `vendor/` and review the git diff, or run `vendor/update.sh` (three-way merge that preserves `.env` and your edits; conflicts get standard merge markers).

Record the tag in the Dokploy project description and in the release notes.

## Override applied

`docker-compose.override.yml` in this directory:

- Attaches only the API gateway (Kong/Envoy) to `dokploy-network` for public ingress.
- Keeps `db`, `studio`, `storage`, `auth`, `rest` on private networks — no host ports, no public domains.
- Adds per-service resource limits.
- Realtime and Edge Runtime remain removed from the base compose per ADR-0002 (deferred).

## Deployed vs vendored

The live OCTO Supabase on Dokploy is the **Dokploy Supabase template** (raw `composeFile` + generated env, Kong gateway, `octo-supabase-*` containers) — `update.sh` does not touch it. Updating the deployed service = bump image tags in the Dokploy composeFile after reviewing `vendor/CHANGELOG.md`, then redeploy. The vendored tree is the version-tracked reference of record for what an upstream release looks like.

## Env

Supabase env vars follow the official `.env.example` — configure values in Dokploy project env, sourced from the secret manager. Generate keys with the vendored `utils/generate-keys.sh` and `utils/add-new-auth-keys.sh` per environment.
