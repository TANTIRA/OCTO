# ADR: Neo4j Bolt Exposure for HTTPS Browser Access

## Status
**Proposed** — Pending decision by @EliteSlacker (decision) and @Aldroun (exec).

## Context
HTTPS Neo4j Browser requires encrypted Bolt (`bolt+s` or `neo4j+s` protocol), but the current deployment keeps Bolt private on `dokploy-network` with TLS disabled. Remote users cannot browse the graph without an SSH tunnel.

### Current State
- **Bolt:** `:7687` private on `dokploy-network`, unencrypted
- **Browser:** `:7474` public, HTTPS via Traefik
- **Access:** SSH tunnel to localhost → HTTP Browser (documented in `deploy/README.md`)
- **UX friction:** Extra step; requires SSH access to VPS

### Problem Statement
Either:
1. Keep the tunnel-only posture (operational burden on users), OR
2. Publicly expose Bolt with TLS (easier for users, wider attack surface)

## Decision Options

### Option A: Keep Tunnel-Only (Status Quo)
**Approach:** No changes. Users SSH-tunnel to access Browser locally.

**Pros:**
- Bolt never touches the internet — zero public exposure
- No new infrastructure or TLS management
- Clear security perimeter: Bolt = internal only

**Cons:**
- Requires SSH access to production VPS
- Extra operational step for each browsing session
- Can't be automated by scheduled jobs or CI/CD pipelines
- Harder to share access across team

**Effort:** None.

### Option B: Traefik TLS-Terminating TCP Router
**Approach:** Expose Bolt on a public domain (e.g., `bolt-octo.mesta.click:6687`) with TLS termination in Traefik. Internal Bolt remains unencrypted on `dokploy-network`.

**Architecture:**
```
Public TLS (bolt+s://) ──Traefik decrypts──> Internal unencrypted Bolt
                             ↓
                    Neo4j auth enforced
```

**Pros:**
- HTTPS Browser works directly from any network
- Can be called from CI/CD or scheduled jobs
- Team can share access without SSH
- Traefik provides connection logging/audit trail
- Can add optional client-cert auth layer (mTLS)
- TLS certificate auto-renewed by Let's Encrypt

**Cons:**
- Bolt publicly routable (wider attack surface)
- Single gate: Neo4j password (no IP allowlist)
- Requires TLS certificate management
- Introduces new Traefik config (TCP router, not HTTP)
- Need to rotate Neo4j password regularly

**Security Mitigations:**
- Strong, random Neo4j password (32+ chars)
- Regular credential rotation
- Monitor Traefik/Neo4j logs for brute-force attempts
- (Optional) Add Traefik basicAuth middleware for extra layer
- (Optional) Restrict `:6687` port at OS firewall

**Effort:** ~2–4 hours
- Generate/manage TLS cert (1 hour)
- Traefik TCP router config (1 hour)
- Testing, monitoring, docs (1–2 hours)

**Provided Artifacts:**
- `traefik-bolt-entrypoint.yml` — static config (new entryPoint `:6687`, ACME resolver)
- `traefik-bolt-tcp-router.yml` — dynamic config (TCP router definition)
- `setup-bolt-tls.sh` — certificate provisioning (self-signed or Let's Encrypt)
- `docker-compose.neo4j-bolt-example.yml` — Neo4j config snippet
- `verify-bolt-tls.sh` — validation script
- `BOLT-TLS-ROUTER.md` — deployment guide + troubleshooting

## Recommendation

**Recommend Option B** if:
- Team size > 3 and wants shared graph browsing
- CI/CD or scheduled jobs need to query the graph
- Production uptime justifies infrastructure investment

**Stick with Option A** if:
- Only one or two ops people browse the graph
- Graph queries are rare or low-risk
- Prefer simpler threat model over convenience

## Implementation Plan (If Approved)

### Phase 1: Staging
1. Spin up staging Neo4j + test Traefik TCP router locally
2. Validate TLS cert chain and Browser connection
3. Load-test with concurrent connections (if graph is large)
4. Document any operational surprises

### Phase 2: Production
1. Run `setup-bolt-tls.sh` on Dokploy host (Let's Encrypt)
2. Copy Traefik configs to `/etc/dokploy/traefik/`
3. Restart Traefik, watch logs for 5 min
4. Assign `bolt-octo.mesta.click:6687` in Dokploy UI (Neo4j Browser domain)
5. Test from Browser and CLI (`cypher-shell`)
6. Update `deploy/README.md` to reflect new HTTPS path + deprecate tunnel

### Phase 3: Monitoring
- Traefik metrics: connection count, latency, error rate
- Neo4j logs: query patterns, slow queries, auth failures
- Set alerting on connection failures or spike in auth errors

## Consequences

**If approved:**
- Neo4j Browser becomes directly accessible to any authenticated user
- Traefik becomes a critical component (TLS termination failure = Browser down)
- Need incident response for password leaks or suspicious query patterns

**If rejected:**
- Team continues using SSH tunnels
- May limit adoption of graph queries in automation

## References
- Issue #300 (this decision)
- `infra/README.md` (current topology)
- `deploy/README.md` (Browser access section)
- `docs/adr/0004-neo4j-graph-store.md` (graph store rationale)
