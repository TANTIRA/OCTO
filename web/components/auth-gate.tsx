"use client";

import { useEffect, useState, type ReactNode } from "react";
import { supabase } from "@/lib/supabase";

/**
 * Client-side session gate for the app shell.
 *
 * - Supabase configured + no session → redirect to /login.
 * - Supabase unconfigured → render children (dev/misconfig path; the API
 *   boundary still rejects unauthenticated calls, so nothing real leaks).
 * - Session present → render children; SIGNED_OUT event returns to /login.
 *
 * The initial render is a neutral loading surface to avoid flashing the
 * shell at unauthenticated visitors.
 */
export default function AuthGate({ children }: { children: ReactNode }) {
  const [allowed, setAllowed] = useState(false);

  useEffect(() => {
    if (!supabase) {
      setAllowed(true);
      return;
    }

    let mounted = true;
    supabase.auth.getSession().then(({ data }) => {
      if (!mounted) return;
      if (data.session) {
        setAllowed(true);
      } else {
        window.location.replace("/login");
      }
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
  }, []);

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
