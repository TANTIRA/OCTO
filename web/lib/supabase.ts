import { createClient, type SupabaseClient } from "@supabase/supabase-js";
import { isRecoveryCallback } from "./password-reset";

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
 * Whether this page load is a password-reset link (#498). Read before the
 * client exists: initialising it consumes and clears the URL fragment, and
 * the PASSWORD_RECOVERY event fires only after INITIAL_SESSION.
 */
export const landedFromRecoveryLink: boolean =
  typeof window !== "undefined" && isRecoveryCallback(window.location.href);

export const supabase: SupabaseClient | null =
  supabaseUrl && supabaseAnonKey
    ? createClient(supabaseUrl, supabaseAnonKey)
    : null;
