/**
 * The client-facing host for host-based routing — the public site and the
 * admin-* ops surface share one image, split on host (see middleware.ts).
 *
 * Prefers `x-forwarded-host` (first entry when proxies chain): the platform
 * proxy overwrites it with the client's original Host, so it survives proxy
 * normalization that would rewrite `host` itself. Falls back to `host` for
 * direct traffic. No path aliases so node:test can import it directly.
 */
export function requestHost(headers: {
  get(name: string): string | null;
}): string {
  const forwarded = headers.get("x-forwarded-host");
  if (forwarded) return forwarded.split(",")[0].trim();
  return headers.get("host") ?? "";
}
