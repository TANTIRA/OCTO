# infra/supabase — vendored Supabase distribution

Do not hand-maintain a Supabase compose file. Vendor the official pinned release and layer `docker-compose.override.yml` on top.

## Setup

```bash
# fetch the pinned release's docker/ directory
git clone --depth 1 --branch self-hosted/v0.8.1 https://github.com/supabase/supabase /tmp/supabase
cp -rf /tmp/supabase/docker/. ./vendor/          # reviewed, then committed or uploaded to Dokploy
```

Record the tag in the Dokploy project description and in the release notes. Upgrade = new tag + diff review + staging rehearsal.

## Override applied

`docker-compose.override.yml` in this directory:

- Attaches only the API gateway (Kong/Envoy) to `dokploy-network` for public ingress.
- Keeps `db`, `studio`, `storage`, `auth`, `rest` on private networks — no host ports, no public domains.
- Adds per-service resource limits.
- Realtime and Edge Runtime remain removed from the base compose per ADR-0002 (deferred).

## Env

Supabase env vars follow the official `.env.example` — configure values in Dokploy project env, sourced from the secret manager. Generate keys with the vendored `utils/generate-keys.sh` and `utils/add-new-auth-keys.sh` per environment.
