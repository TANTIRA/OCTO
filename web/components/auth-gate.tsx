"use client";

import { useCallback, useEffect, useState, type ReactNode } from "react";
import { supabase } from "@/lib/supabase";

/**
 * Client-side session gate for the app shell.
 *
 * - Supabase configured + no session → redirect to /login.
 * - Supabase unconfigured → render children (dev/misconfig path; the API
 *   boundary still rejects unauthenticated calls, so nothing real leaks).
 * - Session present → render children; SIGNED_OUT event returns to /login.
 * - Check fails (network, SDK throw) → error surface with a retry. Never a
 *   silent spinner: a rejected promise would otherwise pin `allowed` false
 *   forever and look like an eternal load.
 *
 * The initial render is a neutral loading surface to avoid flashing the
 * shell at unauthenticated visitors.
 */
export default function AuthGate({ children }: { children: ReactNode }) {
  const [allowed, setAllowed] = useState(false);
  const [failed, setFailed] = useState(false);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    if (!supabase) {
      setAllowed(true);
      return;
    }

    let mounted = true;
    setFailed(false);
    supabase.auth
      .getSession()
      .then(({ data }) => {
        if (!mounted) return;
        if (data.session) {
          setAllowed(true);
        } else {
          window.location.replace("/login");
        }
      })
      .catch(() => {
        if (!mounted) return;
        setFailed(true);
      });

    const {
      data: { subscription },
    } = supabase.auth.onAuthStateChange((event) => {
      if (event === "SIGNED_OUT") window.location.replace("/login");
    });

    return () => {
      mounted = false;
      subscription.unsubscribe();
    };
  }, [attempt]);

  const retry = useCallback(() => setAttempt((n) => n + 1), []);

  if (failed) {
    return (
      <div className="flex h-dvh w-full flex-col items-center justify-center gap-3 bg-white px-6 text-center dark:bg-neutral-950">
        <p role="alert" className="text-[13px] text-neutral-600 dark:text-neutral-400">
          Could not verify your session.
        </p>
        <button
          type="button"
          onClick={retry}
          className="inline-flex h-8 cursor-pointer items-center rounded-[var(--rb-r-sm,6px)] bg-[var(--rb-accent,oklch(20.5%_0_0))] px-3 text-[13px] font-medium text-[var(--rb-accent-fg,oklch(100%_0_0))] dark:bg-[var(--rb-accent,oklch(20.5%_0_0))] dark:text-[var(--rb-accent-fg,oklch(100%_0_0))]"
        >
          Retry
        </button>
      </div>
    );
  }

  if (!allowed) {
    return (
      <div className="flex h-dvh w-full items-center justify-center bg-white dark:bg-neutral-950">
        <div
          role="status"
          aria-label="Checking session"
          className="h-6 w-6 animate-spin rounded-full border-2 border-neutral-300 border-t-neutral-900 dark:border-neutral-700 dark:border-t-neutral-100"
        />
      </div>
    );
  }

  return <>{children}</>;
}
