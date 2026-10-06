"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";

export type Density = "comfortable" | "compact";
export type Locale = "en" | "id";
export type Theme = "light" | "dark" | "system";

type Prefs = {
  density: Density;
  sidebarCollapsed: boolean;
  locale: Locale;
};

type PrefsContext = Prefs & {
  theme: Theme;
  /** The applied appearance after resolving "system" against the OS setting. */
  resolvedTheme: "light" | "dark";
  setTheme: (t: Theme) => void;
  setDensity: (d: Density) => void;
  setSidebarCollapsed: (c: boolean) => void;
  setLocale: (l: Locale) => void;
};

const DEFAULTS: Prefs = { density: "compact", sidebarCollapsed: false, locale: "en" };
const KEY = "octo.app.prefs";
/** V3 THEME-004: theme lives under its own key so other surfaces can read it. */
const THEME_KEY = "octo-theme";

function readTheme(): Theme {
  try {
    const t = window.localStorage.getItem(THEME_KEY);
    return t === "dark" || t === "system" ? t : "light";
  } catch {
    return "light";
  }
}

/** Row heights per density (parity backlog TABLE-002): compact 56px, comfortable 64px. */
export const ROW_HEIGHT: Record<Density, string> = { comfortable: "h-16", compact: "h-14" };
export const ROW_PX: Record<Density, number> = { comfortable: 64, compact: 56 };

const Ctx = createContext<PrefsContext | null>(null);

function read(): Prefs {
  try {
    const raw = window.localStorage.getItem(KEY);
    const stored = { ...DEFAULTS, ...(raw ? (JSON.parse(raw) as Partial<Prefs>) : {}) };
    if (!(stored.density in ROW_HEIGHT)) stored.density = "compact";
    // Keys from earlier versions (e.g. theme) are ignored: the dashboard is light-only (DS-002).
    return { density: stored.density, sidebarCollapsed: !!stored.sidebarCollapsed, locale: stored.locale };
  } catch {
    return DEFAULTS;
  }
}

/**
 * Global view preferences, stored per browser. Density lives here — not in
 * per-page toggles — so every table agrees.
 */
export function PreferencesProvider({ children }: { children: React.ReactNode }) {
  const [prefs, setPrefs] = useState<Prefs>(DEFAULTS);
  const [theme, setThemeState] = useState<Theme>("light");
  const [systemDark, setSystemDark] = useState(false);

  useEffect(() => {
    setPrefs(read());
    setThemeState(readTheme());
    const mq = window.matchMedia("(prefers-color-scheme: dark)");
    setSystemDark(mq.matches);
    const on = (e: MediaQueryListEvent) => setSystemDark(e.matches);
    mq.addEventListener("change", on);
    return () => mq.removeEventListener("change", on);
  }, []);

  const setTheme = useCallback((t: Theme) => {
    setThemeState(t);
    try {
      window.localStorage.setItem(THEME_KEY, t);
    } catch {
      /* storage blocked — the choice still applies for this visit */
    }
  }, []);
  const resolvedTheme: "light" | "dark" = theme === "system" ? (systemDark ? "dark" : "light") : theme;

  const update = useCallback((patch: Partial<Prefs>) => {
    setPrefs((p) => {
      const next = { ...p, ...patch };
      try {
        window.localStorage.setItem(KEY, JSON.stringify(next));
      } catch {
        /* storage blocked — keep the in-memory preference */
      }
      return next;
    });
  }, []);

  const value = useMemo<PrefsContext>(
    () => ({
      ...prefs,
      theme,
      resolvedTheme,
      setTheme,
      setDensity: (density) => update({ density }),
      setSidebarCollapsed: (sidebarCollapsed) => update({ sidebarCollapsed }),
      setLocale: (locale) => update({ locale }),
    }),
    [prefs, update, theme, resolvedTheme, setTheme],
  );

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function usePreferences(): PrefsContext {
  const v = useContext(Ctx);
  if (!v) throw new Error("usePreferences must be used inside PreferencesProvider");
  return v;
}
