/**
 * Formatting standards (plan §32). One place decides how money, ratios,
 * multiples, and dates read across the app, in English and Indonesian.
 *
 *   $812.4M · 18.2% · 1.64× · +2.4 pts · 30 Sep 2026, 13:42
 *
 * Every function takes an optional locale; components normally use
 * `useFormat()` from `lib/use-format`, which binds the user's preference.
 */

export type Locale = "en" | "id";

const TAG: Record<Locale, string> = { en: "en-US", id: "id-ID" };
const MINUS = "−";

const MONTHS: Record<Locale, string[]> = {
  en: ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"],
  id: ["Jan", "Feb", "Mar", "Apr", "Mei", "Jun", "Jul", "Agu", "Sep", "Okt", "Nov", "Des"],
};

const UNITS: Record<Locale, [number, string][]> = {
  en: [
    [1e12, "T"],
    [1e9, "B"],
    [1e6, "M"],
    [1e3, "K"],
  ],
  id: [
    [1e12, "T"],
    [1e9, "M"],
    [1e6, "Jt"],
    [1e3, "Rb"],
  ],
};

/** Plain number with locale separators and fixed digits. */
export function num(v: number, digits = 0, locale: Locale = "en"): string {
  const s = new Intl.NumberFormat(TAG[locale], { minimumFractionDigits: digits, maximumFractionDigits: digits }).format(Math.abs(v));
  return v < 0 ? `${MINUS}${s}` : s;
}

function compactAbs(v: number, digits: number, locale: Locale): string {
  const abs = Math.abs(v);
  for (const [size, unit] of UNITS[locale]) {
    if (abs >= size) return `${num(abs / size, digits, locale)}${unit}`;
  }
  return num(abs, 0, locale);
}

/** $486.2M — summaries, KPIs, cards. Negative values in parentheses (accounting convention). */
export function money(v: number, digits = 1, locale: Locale = "en"): string {
  const s = `$${compactAbs(v, digits, locale)}`;
  return v < 0 ? `(${s})` : s;
}

/** $486,213,400 — tables, exports, and detail views that need full precision. */
export function moneyFull(v: number, locale: Locale = "en"): string {
  const s = `$${num(Math.abs(v), 0, locale)}`;
  return v < 0 ? `(${s})` : s;
}

/** 18.2% */
export function pct(v: number, digits = 1, locale: Locale = "en"): string {
  return `${num(v, digits, locale)}%`;
}

/** +2.4 pts / −1.2% / +0.05× / +$42.0M — deltas always carry an explicit sign and a real minus. */
export function delta(v: number, unit: "%" | "pts" | "pp" | "×" | "$" | "" = "%", digits = 1, locale: Locale = "en"): string {
  const sign = v > 0 ? "+" : v < 0 ? MINUS : "±";
  const abs = Math.abs(v);
  if (unit === "$") return `${sign}$${compactAbs(abs, digits, locale)}`;
  if (unit === "×") return `${sign}${num(abs, 2, locale)}×`;
  if (unit === "pts" || unit === "pp") return `${sign}${num(abs, digits, locale)} pts`;
  if (unit === "") return `${sign}${num(abs, digits, locale)}`;
  return `${sign}${num(abs, digits, locale)}%`;
}

/** 1.64× */
export function multiple(v: number, locale: Locale = "en"): string {
  return `${num(v, 2, locale)}×`;
}

/** 30 Sep 2026 — absolute, for anything where ambiguity matters. Always UTC. */
export function date(iso: string, locale: Locale = "en"): string {
  const d = new Date(iso);
  return `${d.getUTCDate()} ${MONTHS[locale][d.getUTCMonth()]} ${d.getUTCFullYear()}`;
}

/** 13:42 UTC */
export function time(iso: string): string {
  const d = new Date(iso);
  return `${String(d.getUTCHours()).padStart(2, "0")}:${String(d.getUTCMinutes()).padStart(2, "0")} UTC`;
}

/** 30 Sep 2026, 13:42 */
export function dateTime(iso: string, locale: Locale = "en"): string {
  const d = new Date(iso);
  return `${date(iso, locale)}, ${String(d.getUTCHours()).padStart(2, "0")}:${String(d.getUTCMinutes()).padStart(2, "0")}`;
}

/** 1 Jul – 30 Sep 2026 (collapses the shared year). */
export function dateRange(fromIso: string, toIso: string, locale: Locale = "en"): string {
  const a = new Date(fromIso);
  const b = new Date(toIso);
  const sameYear = a.getUTCFullYear() === b.getUTCFullYear();
  const left = sameYear ? `${a.getUTCDate()} ${MONTHS[locale][a.getUTCMonth()]}` : date(fromIso, locale);
  return `${left} – ${date(toIso, locale)}`;
}

const AGO: Record<Locale, { now: string; m: string; h: string; d: string }> = {
  en: { now: "just now", m: "m ago", h: "h ago", d: "d ago" },
  id: { now: "baru saja", m: " mnt lalu", h: " jam lalu", d: " hr lalu" },
};

/** 2h ago — relative to `now`, for activity streams. Falls back to an absolute date after a week. */
export function ago(iso: string, now: Date = new Date(), locale: Locale = "en"): string {
  const s = Math.max(0, Math.round((now.getTime() - new Date(iso).getTime()) / 1000));
  const t = AGO[locale];
  if (s < 60) return t.now;
  if (s < 3600) return `${Math.floor(s / 60)}${t.m}`;
  if (s < 86400) return `${Math.floor(s / 3600)}${t.h}`;
  if (s < 86400 * 7) return `${Math.floor(s / 86400)}${t.d}`;
  return date(iso, locale);
}
