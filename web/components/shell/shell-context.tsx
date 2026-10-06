"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";
import type { Crumb } from "./nav-config";

/*
 * Shell context (plan FE-SHELL-001): page-provided breadcrumb and title,
 * recently opened objects for the command menu, and the command-menu handle.
 */

export type RecentObject = { type: string; name: string; href: string };

type ShellCtx = {
  crumbs: Crumb[] | null;
  setCrumbs: (c: Crumb[] | null) => void;
  recent: RecentObject[];
  pushRecent: (o: RecentObject) => void;
  openCommand: () => void;
  setOpenCommand: (fn: () => void) => void;
};

const Ctx = createContext<ShellCtx | null>(null);
const RECENT_KEY = "octo.app.recent";

export function ShellProvider({ children }: { children: React.ReactNode }) {
  const [crumbs, setCrumbs] = useState<Crumb[] | null>(null);
  const [recent, setRecent] = useState<RecentObject[]>([]);
  // A ref, not state: registering the opener must not re-render the tree.
  const openRef = useRef<() => void>(() => undefined);
  const openCommand = useCallback(() => openRef.current(), []);
  const setOpenCommand = useCallback((fn: () => void) => {
    openRef.current = fn;
  }, []);

  useEffect(() => {
    try {
      setRecent(JSON.parse(window.localStorage.getItem(RECENT_KEY) ?? "[]") as RecentObject[]);
    } catch {
      /* ignore */
    }
  }, []);

  const pushRecent = useCallback((o: RecentObject) => {
    setRecent((xs) => {
      const next = [o, ...xs.filter((x) => x.href !== o.href)].slice(0, 6);
      try {
        window.localStorage.setItem(RECENT_KEY, JSON.stringify(next));
      } catch {
        /* ignore */
      }
      return next;
    });
  }, []);

  const value = useMemo<ShellCtx>(() => ({ crumbs, setCrumbs, recent, pushRecent, openCommand, setOpenCommand }), [crumbs, recent, pushRecent, openCommand, setOpenCommand]);
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useShell() {
  const v = useContext(Ctx);
  if (!v) throw new Error("useShell must be used inside ShellProvider");
  return v;
}

/**
 * Object pages call this to extend the breadcrumb and register a recent
 * object, e.g. Invest › Companies › Helios Data Centers.
 */
export function useBreadcrumb(crumbs: Crumb[] | null, recent?: RecentObject) {
  const { setCrumbs, pushRecent } = useShell();
  const key = JSON.stringify(crumbs);
  useEffect(() => {
    setCrumbs(crumbs);
    if (crumbs?.length) document.title = `${crumbs[crumbs.length - 1].label} · OCTO`;
    return () => setCrumbs(null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, setCrumbs]);
  const rkey = recent?.href;
  useEffect(() => {
    if (recent) pushRecent(recent);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [rkey, pushRecent]);
}
