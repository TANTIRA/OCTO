/**
 * Password-reset helpers (#498, #549). No path aliases so node:test can
 * import this file directly. Browser storage stays with the caller.
 */

/** Stricter than the Auth server's floor (GOTRUE_PASSWORD_MIN_LENGTH = 6). */
export const MIN_PASSWORD_LENGTH = 8;

/**
 * True when `href` is the landing URL of a Supabase password-reset link
 * (implicit flow: `#access_token=…&type=recovery`). The Supabase client
 * consumes and clears the URL fragment while it initialises, so callers
 * must evaluate this before the client is created.
 */
export function isRecoveryCallback(href: string): boolean {
  let url: URL;
  try {
    url = new URL(href);
  } catch {
    return false;
  }
  const fragment = new URLSearchParams(url.hash.replace(/^#/, ""));
  return (
    fragment.get("type") === "recovery" ||
    url.searchParams.get("type") === "recovery"
  );
}

/** The validation message for a new password, or null when it is valid. */
export function newPasswordError(
  password: string,
  confirmation: string,
): string | null {
  if (password.length < MIN_PASSWORD_LENGTH) {
    return `Use at least ${MIN_PASSWORD_LENGTH} characters.`;
  }
  if (password.trim().length === 0) {
    return "The password cannot be only spaces.";
  }
  if (password !== confirmation) {
    return "The passwords do not match.";
  }
  return null;
}

/** Unfinished reset session (#549). Shared by the tab and the browser profile. */
export const RECOVERY_PENDING_KEY = "octo.recovery-pending";

/** Session keys that already finished the reset. */
export const RECOVERY_SATISFIED_KEY = "octo.recovery-satisfied";

const FINGERPRINT_PREFIX = "fp:";
const SATISFIED_LIMIT = 8;

export interface RecoveryStore {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

export type RecoveryAccess = {
  tabPending: string | null;
  durablePending: string | null;
  pending: boolean;
};

/** True for a reset grant (implicit tokens or a PKCE code), not a bare `?type=recovery`. */
export function isRecoveryGrant(href: string): boolean {
  let url: URL;
  try {
    url = new URL(href);
  } catch {
    return false;
  }
  const fragment = new URLSearchParams(url.hash.replace(/^#/, ""));
  const type = fragment.get("type") ?? url.searchParams.get("type");
  if (type !== "recovery") return false;
  if (fragment.get("access_token") && fragment.get("refresh_token")) return true;
  return Boolean(url.searchParams.get("code") ?? fragment.get("code"));
}

export function recoveryGrantAccessToken(href: string): string | null {
  if (!isRecoveryGrant(href)) return null;
  try {
    const url = new URL(href);
    const fragment = new URLSearchParams(url.hash.replace(/^#/, ""));
    return fragment.get("access_token");
  } catch {
    return null;
  }
}

/** Marker for a recovery access token. Not the token itself. */
export function tokenFingerprint(token: string): string {
  let h1 = 0x811c9dc5;
  let h2 = 0x811c9dc5 ^ 0x01000193;
  for (let i = 0; i < token.length; i++) {
    const code = token.charCodeAt(i);
    h1 = Math.imul(h1 ^ code, 0x01000193);
    h2 = Math.imul(h2 ^ (code + i), 0x01000193);
  }
  return `${FINGERPRINT_PREFIX}${(h1 >>> 0).toString(16)}${(h2 >>> 0).toString(16)}`;
}

/** Remember this tab opened a recovery grant. Does not clobber a bound session id. */
export function noteRecoveryGrant(href: string, store: RecoveryStore): void {
  const token = recoveryGrantAccessToken(href);
  if (!token) return;
  const current = store.getItem(RECOVERY_PENDING_KEY);
  if (current && !current.startsWith(FINGERPRINT_PREFIX)) return;
  store.setItem(RECOVERY_PENDING_KEY, tokenFingerprint(token));
}

export function writeRecoveryPending(store: RecoveryStore, value: string | null): void {
  if (value) store.setItem(RECOVERY_PENDING_KEY, value);
  else store.removeItem(RECOVERY_PENDING_KEY);
}

function decodeJwtPayload(token: string): Record<string, unknown> | null {
  const segment = token.split(".")[1];
  if (!segment) return null;
  try {
    const base64 = segment.replace(/-/g, "+").replace(/_/g, "/");
    const pad = base64.length % 4 === 0 ? "" : "=".repeat(4 - (base64.length % 4));
    const binary = atob(base64 + pad);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    const value = JSON.parse(new TextDecoder().decode(bytes)) as unknown;
    if (!value || typeof value !== "object" || Array.isArray(value)) return null;
    return value as Record<string, unknown>;
  } catch {
    return null;
  }
}

export function recoverySessionKey(accessToken: string): string | null {
  const payload = decodeJwtPayload(accessToken);
  const sessionId = payload?.session_id;
  if (typeof sessionId === "string" && sessionId.length > 0) return sessionId;
  const signature = accessToken.split(".")[2];
  if (!signature || signature.length < 8) return null;
  return `sig:${signature.slice(0, 32)}`;
}

export function accessTokenIsRecovery(accessToken: string): boolean {
  const amr = decodeJwtPayload(accessToken)?.amr;
  if (!Array.isArray(amr)) return false;
  return amr.some((entry) => {
    if (!entry || typeof entry !== "object") return false;
    return (entry as { method?: unknown }).method === "recovery";
  });
}

export function readSatisfiedSessions(store: RecoveryStore): string[] {
  const raw = store.getItem(RECOVERY_SATISFIED_KEY);
  if (!raw) return [];
  try {
    const parsed = JSON.parse(raw) as unknown;
    if (!Array.isArray(parsed)) return [];
    return parsed.filter((item): item is string => typeof item === "string" && item.length > 0);
  } catch {
    return [];
  }
}

export function isRecoverySatisfied(store: RecoveryStore, sessionKey: string): boolean {
  return readSatisfiedSessions(store).includes(sessionKey);
}

export function rememberRecoverySatisfied(store: RecoveryStore, sessionKey: string): void {
  if (!sessionKey) return;
  const next = readSatisfiedSessions(store).filter((id) => id !== sessionKey);
  next.push(sessionKey);
  store.setItem(RECOVERY_SATISFIED_KEY, JSON.stringify(next.slice(-SATISFIED_LIMIT)));
}

function isFingerprint(value: string | null): boolean {
  return Boolean(value?.startsWith(FINGERPRINT_PREFIX));
}

/**
 * Whether `sessionKey` is an unfinished reset (#549). A stored session id
 * survives navigation and new tabs. The grant fingerprint and recovery event
 * cover the landing document. An ordinary session is not pending. `satisfied`
 * stops a recovery `amr` from sending the user back after the password is saved.
 */
export function decideRecoveryAccess(args: {
  tabPending: string | null;
  durablePending: string | null;
  sessionKey: string | null;
  tokenIsRecovery: boolean;
  fromRecoveryEvent: boolean;
  grantMatchesSession: boolean;
  satisfied: boolean;
}): RecoveryAccess {
  let { tabPending, durablePending } = args;
  const { sessionKey, tokenIsRecovery, fromRecoveryEvent, grantMatchesSession, satisfied } = args;

  if (!sessionKey) {
    if (isFingerprint(tabPending)) tabPending = null;
    return { tabPending, durablePending, pending: false };
  }

  if (satisfied) {
    if (tabPending === sessionKey || isFingerprint(tabPending)) tabPending = null;
    if (durablePending === sessionKey) durablePending = null;
    return { tabPending, durablePending, pending: false };
  }

  if (tokenIsRecovery || fromRecoveryEvent || grantMatchesSession) {
    return { tabPending: sessionKey, durablePending: sessionKey, pending: true };
  }

  if (isFingerprint(tabPending)) tabPending = null;
  const pending = tabPending === sessionKey || durablePending === sessionKey;
  return { tabPending, durablePending, pending };
}
