/**
 * Password-reset helpers (#498). No path aliases so node:test can import
 * this file directly.
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

/**
 * App-side "recovery pending" marker (#549). A reset-link session is a full
 * session in the auth provider, so the link alone cannot gate the app: the
 * marker is armed before the Supabase client consumes the link, narrowed to
 * the user id once the session resolves, and cleared when the password is set
 * or the session ends. Until then the auth gate routes the session only to
 * the set-new-password form, and the form is offered only to such a session.
 *
 * Stored in localStorage, not sessionStorage: a reset link opened in a second
 * tab shares the session and must be gated the same way.
 */
const RECOVERY_PENDING_KEY = "octo.recovery-pending";

type RecoveryStore = Pick<Storage, "getItem" | "setItem" | "removeItem">;

/** Arms the marker before the link's tokens are consumed — the user id is not known yet. */
export function markRecoveryPending(store: RecoveryStore): void {
  store.setItem(RECOVERY_PENDING_KEY, "");
}

/** Narrows an armed marker to the user the recovery session resolved to. */
export function armRecoveryFor(store: RecoveryStore, userId: string): void {
  store.setItem(RECOVERY_PENDING_KEY, userId);
}

/**
 * Whether [userId] must still pass the set-new-password form. An armed marker
 * with no user yet matches any session — the gate can run before the reset
 * page has narrowed it.
 */
export function recoveryPendingFor(store: RecoveryStore, userId: string): boolean {
  const held = store.getItem(RECOVERY_PENDING_KEY);
  return held !== null && (held === "" || held === userId);
}

export function clearRecoveryPending(store: RecoveryStore): void {
  store.removeItem(RECOVERY_PENDING_KEY);
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
