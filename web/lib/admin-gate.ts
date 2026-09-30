/**
 * The ops-surface verdict (#312) from a `/api/v1/me/access` body. Platform
 * admin is the API's `OCTO_PLATFORM_ADMINS` list — the same gate the
 * `/api/v1/admin/*` endpoints enforce — surfaced as `platformAdmin`.
 *
 * Fails closed: anything but a literal `true` (missing field, an API build
 * that predates it, a truthy string, an error body) is not an admin.
 * No path aliases here so node:test can import it directly.
 */
export function isPlatformAdmin(body: unknown): boolean {
  return (
    typeof body === "object" &&
    body !== null &&
    (body as { platformAdmin?: unknown }).platformAdmin === true
  );
}
