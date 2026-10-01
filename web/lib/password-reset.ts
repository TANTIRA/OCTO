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
