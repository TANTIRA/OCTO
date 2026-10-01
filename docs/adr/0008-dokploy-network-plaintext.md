# ADR-0008: Plaintext HTTP for Internal `dokploy-network` Traffic

- Status: Proposed
- Date: 2026-10-01
- Risk tier: T2 (infrastructure, auth root of trust)
- Decision owner: nominated @EliteSlacker (decision) / @Aldroun (exec) per issue #320 — not
  decided by this document. Same nomination pattern as ADR-0006 (#300); the repo owner has
  final sign-off.
- Issue: #320
- Related: [ADR-0006](0006-neo4j-bolt-exposure.md) (bolt-over-`dokploy-network`, the same
  question for a different protocol), `deploy/dokploy.compose.yml`, `deploy/README.md`,
  `docs/backlog-tracker.md` item 22

## Context

`api`, `agents`, and `web` reach each other over `dokploy-network`, a private Docker bridge
network with no public ports — Traefik is the only public surface and terminates TLS at the
edge (`WHITEPAPER.md` §"public surface"; ADR-0006 confirms this same model for bolt). Several
URLs on that network are plain `http://`, not `https://`:

| Variable | Value | Carries |
| --- | --- | --- |
| `OCTO_AGENTS_BASE_URL` | `http://agents:8080` (default) | The api calling the sidecar |
| `OCTO_API_BASE_URL` | `http://api:8080` | The sidecar calling back into the api — service-principal bearer JWT |
| `API_INTERNAL_URL` | `http://api:8080` (web build arg) | web's same-origin API proxy target |
| `AUTH_JWKS_URL` | internal Kong path, `http://...kong:8000/auth/v1/.well-known/jwks.json` | The JWKS root of trust every bearer token is verified against |

(`deploy/dokploy.compose.yml`, `deploy/README.md`.) Issue #320 additionally cited `NEO4J_URI`
as plaintext — that variable is **not present** on the api service; `deploy/README.md` notes
"No `SUPABASE_*` or `NEO4J_*` on the api: nothing reads them (#340)," so that part of the
original citation is stale and is not carried into this decision.

The threat #320 names: **a compromised neighbor container on `dokploy-network` can read or
tamper with this traffic** — stealing the service-principal JWT the sidecar sends to the api,
or substituting a forged JWKS response that every bearer token would then validate against.
Neither requires breaching the edge; it requires one other container on the same bridge
network being compromised.

ADR-0006 already flagged the same shape of risk for bolt and deferred it here by name:
"Bolt traffic between containers on `dokploy-network` is unencrypted, and that network also
carries bearer tokens and JWKS URLs in plaintext. Tracked separately as backlog item 22"
(`docs/backlog-tracker.md` item 22 = this issue, #320).

## Decision

**Not made here.** This ADR exists to write the trade-off down so #320's nominated owners
decide with the options in front of them, the same separation ADR-0006 used (§"the deciding
factor" / §"revisit trigger") — I'm authoring the document, not the decision.

### Option A — Accept, confined to the private network (status quo)

Keep `dokploy-network` plaintext, matching the posture ADR-0006 already accepted for bolt:
Traefik terminates TLS at the only public ingress, and `dokploy-network` has no published
ports — the attack requires an already-compromised container on that same private network,
not an external attacker. This option costs nothing and changes nothing.

**Residual risk, stated plainly (ADR-0006's own standard):** the blast radius of *any* single
compromised container on `dokploy-network` currently includes every other container's
traffic on that network, including the auth root of trust. That is a lateral-movement
amplifier: a compromise that would otherwise be contained to one service can now intercept
or forge credentials for the others. This is a materially different risk shape than bolt
(ADR-0006): bolt today has no programmatic consumer, so there is nothing to intercept; the
api/agents/web traffic here is live, credentialed, and constant.

### Option B — mTLS or network-policy segmentation between the three services

Terminate TLS (or require mutual TLS) between `api`, `agents`, and `web` on `dokploy-network`,
or segment the network so a compromised container cannot reach traffic that isn't addressed
to it (e.g., per-pair networks, or a sidecar proxy enforcing policy). This closes the
lateral-movement gap Option A accepts.

**Cost:** certificate issuance and rotation for three internal services (none of which has a
public domain to anchor a cert request today — Dokploy's TLS automation covers Traefik's
public routers, not container-to-container links), or a network topology change to
`deploy/dokploy.compose.yml` that the current single-bridge-network model doesn't have a
precedent for anywhere else in this deployment. Meaningfully more operational surface than
Option A, and — unlike ADR-0006's bolt case — there's no "no consumer yet" argument available
to defer it: this traffic is live today.

## Recommendation (non-binding)

Option A, **with the residual risk now written down** rather than only implicit — matching
ADR-0006's own reasoning that the real question is "does a consumer exist that justifies the
operational cost," and the mitigating fact that `dokploy-network` already has no published
ports (the same perimeter ADR-0006 relies on). This is a recommendation for the nominated
owners to accept, modify, or reject — not a decision this document makes.

If Option A is accepted, the open item becomes: does the lateral-movement amplifier this ADR
names change the calculus enough to revisit later (e.g., once a second untrusted workload
ever shares `dokploy-network`)? That revisit trigger should be stated explicitly in whichever
option is accepted, the way ADR-0006 §4 states one for bolt.

## Consequences

### If Option A is accepted

- No new work; the existing posture is now a written decision instead of an implicit one.
- The residual risk (compromised-neighbor credential theft/JWKS forgery) is accepted and
  documented, not hidden — future security reviews don't have to rediscover it from the
  compose file.

### If Option B is accepted

- Closes the lateral-movement gap for all three internal services.
- Adds certificate lifecycle management (or a network-policy mechanism) with no existing
  precedent in this deployment to build on — scope and cost need to be estimated before
  committing, likely as its own follow-up ADR amendment once a direction is chosen.

## Acceptance criteria

- [ ] Nominated owners (@EliteSlacker, @Aldroun) choose Option A or B, or state a third option
      not considered here.
- [ ] Status updated to Accepted with the chosen option, mirroring ADR-0006's header.
- [ ] If Option A: a revisit trigger is stated (mirroring ADR-0006 §4's "when X lands, this is
      re-opened on the merits").
- [ ] If Option B: a follow-up ADR amendment scopes the certificate/network-policy mechanism
      before implementation begins (T2 — `deploy/`/`infra/` changes need an agreed plan per
      `CLAUDE.md`).
- [ ] `docs/backlog-tracker.md` item 22 updated to point here, same as ADR-0006 closed item 2.

## References

- Issue #320 (this decision)
- [ADR-0006](0006-neo4j-bolt-exposure.md) — the same question already decided for bolt; this
  ADR explicitly deferred the broader question here
- `deploy/dokploy.compose.yml` — the plaintext URLs in question
- `deploy/README.md` — `AUTH_JWKS_URL` internal-path requirement, network topology
- `docs/backlog-tracker.md` item 22 — the open tracker entry this ADR resolves
