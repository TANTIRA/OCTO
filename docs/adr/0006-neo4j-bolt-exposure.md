# ADR-0006: Neo4j Bolt Exposure for Remote Browser Access

- Status: Accepted (Option A — tunnel-only)
- Date: 2026-09-30
- Risk tier: T2 (persistence, tenancy, infrastructure)
- Decision owner: repo owner (@rade-nugroho). Issue #300 nominated @EliteSlacker (decision) /
  @Aldroun (exec); decided by the repo owner.
- Issue: #300
- Depends on: [ADR-0004](0004-neo4j-graph-store.md)
- Related: `infra/README.md`, `deploy/README.md` ("Neo4j Browser access")

## Context

The Neo4j Browser served at `neo4j-octo.mesta.click` is public over HTTPS. Browser sessions
negotiate `bolt+s`/`neo4j+s`, but bolt itself is private to `dokploy-network` with TLS disabled
(`NEO4J_BOLT_TLS_ENABLED: "false"`). No remote bolt connection can therefore complete, so
browsing the deployed graph from a laptop needs an SSH tunnel plus a locally hosted HTTP
Browser — the recipe in `deploy/README.md` ("Neo4j Browser access").

The decision was whether to keep that friction or open bolt publicly through a TLS-terminating
Traefik TCP router.

### The framing that was wrong

The original write-up treated Option A as "zero public exposure" and Option B as "wider attack
surface". That understated A's residual risk, because **the Neo4j Browser is already public on
`mesta.click`**, behind nothing but the `neo4j` password. Neo4j Browser ships a full cypher-shell
web UI: anyone who authenticates to the public Browser already has the query capability a bolt
client would grant. So the delta between the two options is one protocol more, not one degree
more of access.

That reframes the question. Bolt-vs-tunnel was never the real security boundary here. The real
exposure is a public Browser, and it is tracked separately — this ADR does not bless it.

### The deciding factor

Nothing consumes bolt programmatically yet. Per `infra/.env.example`, no API code opens a bolt
connection; the graph writer (ADR-0004, #308) adds the `NEO4J_*` credentials when it lands. The
strongest argument for Option B — "CI/CD or scheduled jobs need to query the graph" — is
therefore currently moot. A public TCP route to a store with zero clients is infrastructure
built ahead of a requirement that does not exist yet.

## Decision

**Keep the tunnel-only posture. Bolt stays private to `dokploy-network`.**

1. **Access path.** Operators reach the graph over SSH, using the loopback socat bridge plus a
   locally hosted HTTP Browser. The bridge binds `127.0.0.1:7687` — never `0.0.0.0`.
2. **No Traefik TCP router.** No bolt entryPoint, no public bolt domain, no certificate to manage
   for `:6687`.
3. **Deployment shape.** Neo4j declares **no `ports:` block**. Publishing `"7687:7687"` binds
   `0.0.0.0:7687` and would put bolt on the public internet with TLS disabled while appearing to
   be internal. Container-name resolution on `dokploy-network` is all Traefik needs.
   `infra/docker-compose.neo4j-bolt-example.yml` carried exactly that latent bug until this
   decision removed the block.
4. **Revisit trigger.** When the graph writer lands (#308) **and** it has a consumer outside the
   `api` — a scheduled job, CI, or a second engineer who needs standing access — Option B is
   re-opened on the merits. Not before.

### Rejected: Option B (TLS-terminating Traefik TCP router)

Exposing bolt on a public domain (e.g. `bolt-octo.mesta.click:6687`) with TLS terminated at
Traefik. Its real merits, recorded so the option is not re-litigated from scratch: remote
browsing with no SSH, machine-callable bolt for automation, shared access without per-person VPS
credentials, and Traefik connection logging.

Why not now: it puts a database protocol on the public internet whose only authentication is a
single shared password with no IP allowlist and no meaningful rate limit (bolt has no HTTP
middleware to rate-limit through), while the legitimate need — programmatic graph access — has
not materialized. Cost was low to defer and the posture is easier to tighten now than to unwind.

### Rejected artifacts

`infra/traefik-bolt-tcp-router.yml`, `infra/traefik-bolt-entrypoint.yml`,
`infra/setup-bolt-tls.sh`, `infra/verify-bolt-tls.sh`, and `infra/BOLT-TLS-ROUTER.md` are
retained as the Option B design record. They are **not applied to any host**, and both YAML
fragments have been corrected since the decision — three defects were found on review, all of
which would have broken the deploy:

1. The TCP router set `certResolver` and `domains` in the same `tls` block; the two are mutually
   exclusive and Traefik rejects the router. The alternative is now commented out instead of
   co-present.
2. The ACME resolver used `httpChallenge`, which Traefik cannot satisfy for a TCP router — the
   challenge handler belongs to an HTTP router and there is no HTTP service on that entryPoint.
   Now `dnsChallenge`.
3. The proposal offered a `basicAuth` middleware as an extra auth layer. **Traefik has no
   password-auth middleware for TCP routers** — `tcp.middlewares` is limited to `inFlightConn`,
   `ipAllowList`, and `ipWhiteList`. The comment now says so, and suggests `ipAllowList` instead.

Defect 3 strengthens this ADR's reasoning: had Option B shipped, the Neo4j password would have
been the *only* authentication in front of the graph store, with no rate limiting (bolt has no
HTTP middleware to rate-limit through) and no allowlist unless one was added by hand. If Option B
is ever revived, fix the credentials and challenge first, and add the allowlist before exposing
the route.

## Consequences

### Positive

- Bolt is unreachable from the internet by configuration and by network membership, with no
  single-password gate in front of it.
- No new TLS surface, entryPoint, certificate, or Traefik dependency to operate or monitor.
- The decision costs nothing: the tunnel path already exists and already works.
- The misleading example compose no longer models a public bolt port.

### Negative

- Browsing the graph stays a two-step, SSH-requiring operation.
- Graph queries cannot be automated from outside `dokploy-network` yet — CI and scheduled jobs
  will have to run on the host or tunnel until the graph writer and its consumers land.
- Bolt traffic between containers on `dokploy-network` is unencrypted, and that network also
  carries bearer tokens and JWKS URLs in plaintext. Tracked separately as backlog item 22.

### Neutralized risks

- Option B would have added a public database protocol guarded by one shared credential, with no
  IP allowlist, no rate limiting and no proxy-layer authentication available — a
  credential-stuffing surface on the graph store.
- Option B would have made Traefik a new single point of failure for database access; a TLS
  termination fault would take graph tooling down alongside the app.

## Acceptance criteria

- [x] Decision recorded here with the rejected option and its rationale preserved.
- [x] `infra/docker-compose.neo4j-bolt-example.yml` publishes no host ports; comment matches
      behavior.
- [x] `deploy/README.md` documents the tunnel as the settled path and points here.
- [x] Tunnel bridge binds loopback only (`127.0.0.1:7687`).
- [x] No Traefik bolt entryPoint or router applied to the host.
- [ ] Revisit when the graph writer (#308) has a consumer outside the `api`.

## References

- Issue #300 (this decision)
- `deploy/README.md` ("Neo4j Browser access") — the operative recipe
- `infra/README.md` — topology and privacy posture
- [ADR-0004](0004-neo4j-graph-store.md) — graph store rationale
- `docs/backlog-tracker.md` — item 2 (closed by this ADR), item 22 (adjacent, open)
