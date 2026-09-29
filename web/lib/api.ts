import { supabase } from "@/lib/supabase";

/**
 * Fetch helper for the OCTO API.
 *
 * Browser calls go to the same-origin `/api/*` path — `next.config.ts`
 * rewrites proxy them to the internal API service, so no CORS surface is
 * exposed. When a Supabase session exists its access token is attached as a
 * bearer token; the API's resource server verifies it against the issuer
 * JWKS. Without a session the call still runs — the API returns 401 and the
 * caller decides how to render that.
 */
export async function apiFetch(
  path: string,
  init: RequestInit = {},
): Promise<Response> {
  const headers = new Headers(init.headers);
  if (supabase && !headers.has("Authorization")) {
    const { data } = await supabase.auth.getSession();
    const token = data.session?.access_token;
    if (token) headers.set("Authorization", `Bearer ${token}`);
  }
  return fetch(path, { ...init, headers });
}

export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

async function parse<T>(res: Response): Promise<T> {
  if (!res.ok) throw new ApiError(res.status, `HTTP ${res.status}`);
  return (await res.json()) as T;
}

export async function getJson<T>(path: string): Promise<T> {
  return parse<T>(await apiFetch(path));
}

export async function postJson<T>(path: string, body: unknown): Promise<T> {
  return parse<T>(
    await apiFetch(path, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    }),
  );
}

/** Shared render hint for tenant-scoped reads and gated writes. */
export function messageFor(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.status === 401) return "sign in required";
    if (err.status === 403) return "no access to this resource";
    if (err.status === 404) return "not found (or outside your tenants)";
    if (err.status === 409) return "conflict — the resource is mid-transition";
    if (err.status === 429) return "rate limited — retry shortly";
    if (err.status === 503) return "the backing service is unavailable";
    return `request failed (HTTP ${err.status})`;
  }
  return "could not reach the API";
}

/** The caller's tenant memberships drive every tenant-scoped picker in the app. */
export type Tenant = { tenantId: string; slug: string; role: string };

export async function myAccess(): Promise<Tenant[]> {
  const body = await getJson<{ tenants: Tenant[] }>("/api/v1/me/access");
  return body.tenants;
}
