# OCTO Neo4j Bolt: TLS-Terminating Traefik TCP Router
## Architecture Decision: Public TLS Bolt vs. Private Tunnel-Only

### Current State
- **Bolt private:** `:7687` on `dokploy-network`, TLS disabled
- **Workaround:** SSH tunnel + local HTTP Browser (documented in `deploy/README.md`)
- **Problem:** HTTPS Neo4j Browser requires encrypted Bolt (`bolt+s`), so remote access is blocked

### Proposed Solution: Traefik TLS-Terminating TCP Router

Expose Neo4j Bolt via TLS on a public domain (e.g., `bolt-octo.mesta.click:6687`) while terminating the TLS layer in Traefik. The internal Bolt connection remains unencrypted (only reachable on `dokploy-network`).

```
┌─────────────────────────────────────────────┐
│ Public Internet (HTTPS)                     │
└─────────────────────────────────────────────┘
              │ bolt+s://bolt-octo.mesta.click:6687
              ▼
┌─────────────────────────────────────────────┐
│ Traefik TLS Terminator                      │
│  - Validate client cert (optional)          │
│  - Decrypt TLS                              │
│  - (Neo4j auth still applies)               │
└─────────────────────────────────────────────┘
              │ bolt:// (internal)
              ▼
┌─────────────────────────────────────────────┐
│ Neo4j Bolt (:7687 on dokploy-network)       │
│  - Enforces NEO4J_USER / NEO4J_PASSWORD     │
└─────────────────────────────────────────────┘
```

### Files in This Directory

- **`traefik-bolt-entrypoint.yml`**: Static Traefik config
  - Defines `:6687` TCP entryPoint
  - Sets up ACME certificate resolver (Let's Encrypt)
  - Merge into `/etc/dokploy/traefik/traefik.yml`

- **`traefik-bolt-tcp-router.yml`**: Dynamic Traefik config
  - Defines `bolt-tls-router` (TCP router, not HTTP)
  - Maps public `:6687` → internal `:7687`
  - Configures TLS termination + upstream load balancer
  - Place in `/etc/dokploy/traefik/dynamic/traefik-bolt-tcp-router.yml`

- **`setup-bolt-tls.sh`**: Certificate provisioning helper
  - Generates self-signed cert (testing) or requests Let's Encrypt (production)
  - Run once on Dokploy host: `bash setup-bolt-tls.sh`

### Deployment Steps

1. **On the Dokploy host:**
   ```bash
   # Generate certificate (self-signed or Let's Encrypt)
   bash infra/setup-bolt-tls.sh
   
   # Copy configs
   cp infra/traefik-bolt-entrypoint.yml /etc/dokploy/traefik/
   cp infra/traefik-bolt-tcp-router.yml /etc/dokploy/traefik/dynamic/
   
   # Merge traefik-bolt-entrypoint.yml into /etc/dokploy/traefik/traefik.yml
   # (add the entryPoints and certificatesResolvers sections)
   
   # Restart Traefik
   docker compose -f /var/lib/dokploy/compose/docker-compose.yml \
     restart traefik
   ```

2. **Update DNS** (if using Let's Encrypt):
   ```bash
   # Point bolt-octo.mesta.click A record to Dokploy host public IP
   ```

3. **Update Neo4j Browser domain** in Dokploy UI:
   - Assign `bolt-octo.mesta.click:6687` as the Bolt endpoint
   - The Browser will now attempt `bolt+s://bolt-octo.mesta.click:6687`

4. **Test**:
   ```bash
   # From local machine
   openssl s_client -connect bolt-octo.mesta.click:6687
   
   # From Dokploy host
   docker exec -it <neo4j-container> cypher-shell -a bolt+s://bolt-octo.mesta.click:6687 \
     -u neo4j -p $NEO4J_PASSWORD
   ```

### Security Posture

**Pro:**
- Public HTTPS Browser now works (`bolt+s` encrypted)
- TLS between client and Traefik
- Neo4j's native authentication still enforced
- Traefik logs all connections (auditability)
- Can add optional client cert auth (mTLS) via Traefik middleware

**Con:**
- Bolt publicly routable (vs. private-only tunnel)
- Relies on Neo4j password as the only gate (no IP allowlist)
- If Neo4j credentials leak, Bolt is exposed to brute force

**Mitigations:**
- Rotate `NEO4J_PASSWORD` regularly
- Consider Traefik's `basicAuth` middleware for an additional layer (separate from Neo4j auth)
- Monitor Traefik logs for unusual connection patterns
- Use strong, randomly-generated Neo4j password (32+ chars)
- (Optional) Restrict the `:6687` port to specific IP ranges via OS firewall

### Alternative: mTLS (Mutual TLS) + Auth

If tighter security is required, add a Traefik middleware to enforce client certificates:

```yaml
tcp:
  middlewares:
    bolt-mtls:
      clientAuth:
        # Require valid client cert signed by your CA
        secretNames: [client-ca-cert]
```

Then both the client and server must present certificates. The Neo4j Browser doesn't support this natively (it's a web app, not a Bolt driver), so this only works for programmatic clients.

### Troubleshooting

- **"Connection refused"**: Check that Traefik has loaded the dynamic config; watch logs:
  ```bash
  docker logs <traefik-container> | grep bolt
  ```

- **"TLS handshake failure"**: Verify the certificate is valid:
  ```bash
  openssl x509 -in /etc/dokploy/traefik/certs/bolt.crt -noout -text
  ```

- **"No route to host"**: Confirm the entryPoint `:6687` is listening:
  ```bash
  ss -tlnp | grep 6687
  ```

- **Neo4j Browser can't connect**: Ensure Browser URL uses `bolt+s://` scheme. If cert is self-signed, the Browser may reject it; use a trusted CA or disable cert verification (development only).

### Rollback

Delete the Traefik dynamic config and restart:
```bash
rm /etc/dokploy/traefik/dynamic/traefik-bolt-tcp-router.yml
docker restart <traefik-container>
```

Users revert to the SSH-tunnel workaround in `deploy/README.md`.

### Future Considerations

- **Gateway pattern**: Instead of exposing Bolt directly, run a Bolt proxy (e.g., `neo4j-enterprise` external endpoint) that enforces rate limiting and request logging.
- **Audit trail**: Enable Neo4j query logging to `neo4j.log` for forensic purposes.
- **Staged rollout**: Deploy to staging first; validate with the Browser before production.
