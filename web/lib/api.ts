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
