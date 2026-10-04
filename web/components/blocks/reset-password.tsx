"use client";

import { useEffect, useId, useState, type FormEvent } from "react";
import Link from "next/link";
import { Loader2 } from "lucide-react";
import {
  finishPasswordRecovery,
  supabase,
  syncRecoverySession,
  whenRecoverySettled,
} from "@/lib/supabase";
import { MIN_PASSWORD_LENGTH, newPasswordError } from "@/lib/password-reset";
import {
  btnPrimary,
  cx,
  field,
  focus,
  linkClass,
  transition,
} from "@/components/blocks/authentication-3";

type Phase = "checking" | "ready" | "invalid";

const alertClass =
  "rounded-[var(--rb-r-md,8px)] border border-red-200 bg-red-50 px-3 py-2 text-xs text-red-800 dark:border-red-900/50 dark:bg-red-950/40 dark:text-red-200";

/**
 * Set-new-password form (#498, #549). The reset email links here; the
 * Supabase client exchanges the link's tokens for a session while it
 * initialises, so `getSession()` resolves only after that exchange. No
 * session means the link was invalid, expired or already used. An ordinary
 * signed-in session is not this form — only a reset-link session whose new
 * password is still unset.
 */
export default function ResetPassword() {
  const passwordId = useId();
  const confirmId = useId();

  const [phase, setPhase] = useState<Phase>("checking");
  const [password, setPassword] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!supabase) {
      setPhase("invalid");
      return;
    }
    let active = true;
    supabase.auth
      .getSession()
      .then(async ({ data }) => {
        if (!active) return;
        if (!data.session) {
          syncRecoverySession(null);
          setPhase("invalid");
          return;
        }
        const pending = await whenRecoverySettled(data.session);
        if (!active) return;
        if (!pending) {
          window.location.replace("/app");
          return;
        }
        setPhase("ready");
      })
      .catch(() => {
        if (active) setPhase("invalid");
      });
    return () => {
      active = false;
    };
  }, []);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (!supabase || pending) return;
    const invalid = newPasswordError(password, confirmation);
    if (invalid) {
      setError(invalid);
      return;
    }
    setPending(true);
    setError(null);
    const { data } = await supabase.auth.getSession();
    const accessToken = data.session?.access_token;
    if (!accessToken || !syncRecoverySession(data.session)) {
      setError("This reset link is no longer valid. Request a new one from the sign-in page.");
      setPending(false);
      return;
    }
    const { error: updateError } = await supabase.auth.updateUser({
      password,
    });
    if (updateError) {
      setError(updateError.message);
      setPending(false);
      return;
    }
    finishPasswordRecovery(accessToken);
    window.location.replace("/app");
  };

  return (
    <div className="flex h-full min-h-[480px] w-full items-center justify-center bg-white px-6 py-8 dark:bg-neutral-950">
      <div className="w-full max-w-[360px]">
        <h1 className="text-2xl font-medium tracking-[-0.02em] text-neutral-900 dark:text-neutral-50">
          Set a new password
        </h1>

        {phase === "checking" && (
          <p
            role="status"
            className="mt-6 flex items-center gap-2 text-sm text-neutral-500 dark:text-neutral-400"
          >
            <Loader2 aria-hidden="true" className="h-4 w-4 animate-spin" />
            Verifying your reset link…
          </p>
        )}

        {phase === "invalid" && (
          <div className="mt-6 space-y-4">
            <p role="alert" className={alertClass}>
              {supabase
                ? "This reset link is invalid, has expired, or was already used. Request a new one from the sign-in page."
                : "Sign-in is not configured on this deployment — the Supabase env keys are unset. Contact your administrator."}
            </p>
            <Link href="/login" className={cx("text-sm", linkClass, focus)}>
              Back to sign in
            </Link>
          </div>
        )}

        {phase === "ready" && (
          <form onSubmit={submit} noValidate className="mt-6 space-y-4">
            <p className="text-sm text-neutral-500 dark:text-neutral-400">
              Choose a password with at least {MIN_PASSWORD_LENGTH} characters.
            </p>
            <div className="space-y-1.5">
              <label
                htmlFor={passwordId}
                className="block text-sm font-medium text-neutral-900 dark:text-neutral-100"
              >
                New password
              </label>
              <input
                id={passwordId}
                type="password"
                autoComplete="new-password"
                minLength={MIN_PASSWORD_LENGTH}
                required
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                className={cx(field, focus)}
              />
            </div>
            <div className="space-y-1.5">
              <label
                htmlFor={confirmId}
                className="block text-sm font-medium text-neutral-900 dark:text-neutral-100"
              >
                Confirm new password
              </label>
              <input
                id={confirmId}
                type="password"
                autoComplete="new-password"
                required
                value={confirmation}
                onChange={(e) => setConfirmation(e.target.value)}
                className={cx(field, focus)}
              />
            </div>

            {error && (
              <p role="alert" className={alertClass}>
                {error}
              </p>
            )}

            <button
              type="submit"
              disabled={pending}
              className={cx(btnPrimary, transition, focus)}
            >
              {pending && (
                <Loader2 aria-hidden="true" className="h-4 w-4 animate-spin" />
              )}
              Save password
            </button>
          </form>
        )}
      </div>
    </div>
  );
}
