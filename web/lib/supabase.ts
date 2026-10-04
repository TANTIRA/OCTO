import { createClient, type SupabaseClient } from "@supabase/supabase-js";
import {
  accessTokenIsRecovery,
  decideRecoveryAccess,
  isRecoveryCallback,
  isRecoveryGrant,
  isRecoverySatisfied,
  noteRecoveryGrant,
  recoverySessionKey,
  rememberRecoverySatisfied,
  RECOVERY_PENDING_KEY,
  tokenFingerprint,
  writeRecoveryPending,
  type RecoveryStore,
} from "./password-reset";

/**
 * Browser Supabase client for the OCTO app.
 *
 * The anon key is publishable by design — row-level and endpoint security
 * live server-side. When the env pair is absent (unconfigured deployment)
 * the client is `null` and callers render the unauthenticated/dev path.
 */
const supabaseUrl = process.env.NEXT_PUBLIC_SUPABASE_URL;
const supabaseAnonKey = process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY;

/**
 * Whether this document was opened from a reset link. Read before the
 * client exists: initialising it consumes and clears the URL fragment, and
 * the PASSWORD_RECOVERY event fires only after that.
 */
const recoveryLanding =
  typeof window !== "undefined" &&
  (isRecoveryCallback(window.location.href) || isRecoveryGrant(window.location.href));

function browserStore(kind: "localStorage" | "sessionStorage"): RecoveryStore | null {
  if (typeof window === "undefined") return null;
  try {
    return window[kind];
  } catch {
    return null;
  }
}

function readPending(store: RecoveryStore | null): string | null {
  if (!store) return null;
  try {
    return store.getItem(RECOVERY_PENDING_KEY);
  } catch {
    return null;
  }
}

function writePending(store: RecoveryStore | null, value: string | null): void {
  if (!store) return;
  try {
    writeRecoveryPending(store, value);
  } catch {
    // Private mode or a full quota. Callers still honour the returned decision.
  }
}

// The implicit grant's access token is fingerprinted before the client
// strips the fragment, so a later navigation can still recognise the session.
if (typeof window !== "undefined") {
  const tab = browserStore("sessionStorage");
  if (tab) {
    try {
      noteRecoveryGrant(window.location.href, tab);
    } catch {
      // The recovery event and amr claim remain as the other signals.
    }
  }
}

type RecoverySession = { access_token: string } | null;

/**
 * Record an unfinished reset against the current session and report whether
 * the app must keep the user on the set-new-password form (#549).
 */
export function syncRecoverySession(
  session: RecoverySession,
  options?: { fromRecoveryEvent?: boolean },
): boolean {
  const tab = browserStore("sessionStorage");
  const durable = browserStore("localStorage");
  const accessToken = session?.access_token ?? "";
  const sessionKey = accessToken ? recoverySessionKey(accessToken) : null;
  const tokenIsRecovery = accessToken ? accessTokenIsRecovery(accessToken) : false;
  let grantMatchesSession = false;
  if (accessToken && tab) {
    try {
      grantMatchesSession = tab.getItem(RECOVERY_PENDING_KEY) === tokenFingerprint(accessToken);
    } catch {
      grantMatchesSession = false;
    }
  }
  const satisfied = Boolean(
    sessionKey &&
      [tab, durable].some((store) => {
        if (!store) return false;
        try {
          return isRecoverySatisfied(store, sessionKey);
        } catch {
          return false;
        }
      }),
  );
  const decision = decideRecoveryAccess({
    tabPending: readPending(tab),
    durablePending: readPending(durable),
    sessionKey,
    tokenIsRecovery,
    fromRecoveryEvent: Boolean(options?.fromRecoveryEvent),
    grantMatchesSession,
    satisfied,
  });
  writePending(tab, decision.tabPending);
  writePending(durable, decision.durablePending);
  return decision.pending;
}

/**
 * `PASSWORD_RECOVERY` is queued after session initialisation. On a reset
 * landing, wait one turn so that event can bind the session before the UI
 * treats it as an ordinary sign-in.
 */
export function whenRecoverySettled(session: { access_token: string }): Promise<boolean> {
  if (syncRecoverySession(session)) return Promise.resolve(true);
  if (!recoveryLanding || typeof window === "undefined") return Promise.resolve(false);
  return new Promise((resolve) => {
    window.setTimeout(() => resolve(syncRecoverySession(session)), 0);
  });
}

/** The new password is saved; this session may enter the app. */
export function finishPasswordRecovery(accessToken: string): void {
  const sessionKey = recoverySessionKey(accessToken);
  for (const store of [browserStore("sessionStorage"), browserStore("localStorage")]) {
    if (!store) continue;
    try {
      if (sessionKey) rememberRecoverySatisfied(store, sessionKey);
      writeRecoveryPending(store, null);
    } catch {
      // A later sync still sees the satisfied mark when the write landed.
    }
  }
}

function clearRecoveryPendingStores(): void {
  writePending(browserStore("sessionStorage"), null);
  writePending(browserStore("localStorage"), null);
}

export const supabase: SupabaseClient | null =
  supabaseUrl && supabaseAnonKey
    ? createClient(supabaseUrl, supabaseAnonKey)
    : null;

if (supabase && typeof window !== "undefined") {
  supabase.auth.onAuthStateChange((event, session) => {
    if (event === "PASSWORD_RECOVERY") {
      const pending = syncRecoverySession(session, { fromRecoveryEvent: true });
      // A reset opened in another tab replaces the shared auth session. This
      // tab has to leave the app too, until a new password is saved.
      if (pending && !window.location.pathname.startsWith("/login/reset")) {
        window.location.replace("/login/reset");
      }
    } else if (event === "SIGNED_OUT") {
      clearRecoveryPendingStores();
    }
  });
}
