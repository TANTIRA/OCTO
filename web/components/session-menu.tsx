"use client";

import { useEffect, useRef, useState } from "react";
import { ChevronsUpDown, LogOut } from "lucide-react";
import { supabase } from "@/lib/supabase";

const focus =
  "focus-visible:outline-none focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--rb-accent,oklch(20.5%_0_0))] dark:focus-visible:outline-[var(--rb-accent,oklch(100%_0_0))]";

/**
 * Header session affordance: signed-in user's email initial, expanding to
 * the full address and Sign out. When Supabase is unconfigured it renders
 * nothing rather than a dead control.
 */
export default function SessionMenu() {
  const [email, setEmail] = useState<string | null>(null);
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!supabase) return;
    supabase.auth.getUser().then(({ data }) => {
      setEmail(data.user?.email ?? null);
    });
  }, []);

  useEffect(() => {
    if (!open) return;
    const onPointerDown = (event: PointerEvent) => {
      if (!rootRef.current?.contains(event.target as Node)) setOpen(false);
    };
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      setOpen(false);
      triggerRef.current?.focus();
    };
    document.addEventListener("pointerdown", onPointerDown);
    document.addEventListener("keydown", onKeyDown);
    return () => {
      document.removeEventListener("pointerdown", onPointerDown);
      document.removeEventListener("keydown", onKeyDown);
    };
  }, [open]);

  if (!supabase || !email) return null;

  const signOut = async () => {
    await supabase?.auth.signOut();
    window.location.assign("/login");
  };

  return (
    <div ref={rootRef} className="relative">
      <button
        ref={triggerRef}
        type="button"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label={`Session menu for ${email}`}
        onClick={() => setOpen((v) => !v)}
        className={`flex h-8 cursor-pointer items-center gap-1.5 rounded-[var(--rb-r-md,8px)] bg-neutral-100 pl-1.5 pr-2 text-neutral-700 hover:bg-neutral-200 active:bg-neutral-200 dark:bg-neutral-800 dark:text-neutral-300 dark:hover:bg-neutral-700 dark:active:bg-neutral-700 ${focus}`}
      >
        <span className="flex h-5.5 w-5.5 items-center justify-center rounded-[var(--rb-r-sm,6px)] bg-neutral-300 text-[11px] font-medium text-neutral-700 uppercase dark:bg-neutral-600 dark:text-neutral-100">
          {email.charAt(0)}
        </span>
        <ChevronsUpDown aria-hidden="true" className="h-3.5 w-3.5" />
      </button>

      {open && (
        <div
          role="menu"
          className="absolute right-0 top-[calc(100%+0.25rem)] z-30 w-56 rounded-[var(--rb-r-2xl,14px)] border border-neutral-200 bg-white p-1 shadow-[0_4px_16px_-4px_rgba(0,0,0,0.10)] dark:border-neutral-800 dark:bg-neutral-900"
        >
          <p className="truncate px-2 py-1.5 text-xs text-neutral-500 dark:text-neutral-400">
            {email}
          </p>
          <button
            type="button"
            role="menuitem"
            autoFocus
            onClick={signOut}
            className={`flex w-full cursor-pointer items-center gap-2 rounded-[var(--rb-r-lg,10px)] px-2 py-1.5 text-left text-[13px] text-neutral-700 hover:bg-neutral-100 hover:text-neutral-900 dark:text-neutral-300 dark:hover:bg-neutral-800 dark:hover:text-neutral-100 ${focus}`}
          >
            <LogOut aria-hidden="true" className="h-3.5 w-3.5" />
            Sign out
          </button>
        </div>
      )}
    </div>
  );
}
