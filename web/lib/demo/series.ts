/**
 * Demo time series and derived aggregates. Allocation is computed from
 * INVESTMENTS so it always agrees with the holdings table.
 */

import { AS_OF, COMPANIES, FUNDS, INVESTMENTS, PORTFOLIO, companyById, type Fund } from "./entities";

export const QUARTERS = ["Q4 23", "Q1 24", "Q2 24", "Q3 24", "Q4 24", "Q1 25", "Q2 25", "Q3 25", "Q4 25", "Q1 26", "Q2 26", "Q3 26"];

/** Quarter-end portfolio NAV ($M) with a public-market benchmark rebased to the same start. */
export const NAV_SERIES = [612.0, 629.5, 648.1, 671.4, 698.0, 716.2, 731.0, 742.3, 760.1, 779.4, 787.0, 812.4].map((nav, i) => ({
  q: QUARTERS[i],
  nav,
  benchmark: Math.round(612 * Math.pow(1.021, i) * 10) / 10,
  called: [410, 438, 462, 489, 512, 531, 548, 560, 581, 598, 603, 644.8][i],
}));

export type NavRange = "daily" | "weekly" | "monthly" | "quarterly" | "yearly";
export type NavPoint = { date: string; label: string; title: string; nav: number; benchmark: number };

const QUARTER_END = ["2023-12-31", "2024-03-31", "2024-06-30", "2024-09-30", "2024-12-31", "2025-03-31", "2025-06-30", "2025-09-30", "2025-12-31", "2026-03-31", "2026-06-30", "2026-09-30"];
const MON = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
const DAY = 86_400_000;

/**
 * Daily NAV path ($M) interpolated between the reconciled quarter-end marks with
 * a small deterministic wobble that is pinned to zero at every quarter end, so
 * every range ends exactly at $812.4M on 30 Sep 2026 (demo data).
 */
function dailyNav(points: { nav: number; benchmark: number }[]) {
  const out: { t: number; nav: number; benchmark: number }[] = [];
  for (let k = 0; k < points.length - 1; k++) {
    const a = Date.parse(QUARTER_END[k]);
    const b = Date.parse(QUARTER_END[k + 1]);
    const n = Math.round((b - a) / DAY);
    for (let d = 0; d < n; d++) {
      const u = d / n;
      const wobble = Math.sin(u * Math.PI) * (Math.sin(d * 0.9 + k * 2.1) * 0.006 + Math.sin(d * 0.23 + k) * 0.009);
      const nav = points[k].nav + (points[k + 1].nav - points[k].nav) * u;
      const bm = points[k].benchmark + (points[k + 1].benchmark - points[k].benchmark) * u;
      out.push({ t: a + d * DAY, nav: Math.round(nav * (1 + wobble) * 10) / 10, benchmark: Math.round(bm * (1 + wobble * 1.4) * 10) / 10 });
    }
  }
  const last = points[points.length - 1];
  out.push({ t: Date.parse(QUARTER_END[QUARTER_END.length - 1]), nav: last.nav, benchmark: last.benchmark });
  return out;
}

/** ISO-8601 week number, for weekly axis labels. */
function isoWeek(t: number) {
  const d = new Date(t);
  const day = (d.getUTCDay() + 6) % 7;
  d.setUTCDate(d.getUTCDate() - day + 3);
  const first = new Date(Date.UTC(d.getUTCFullYear(), 0, 4));
  return 1 + Math.round(((d.getTime() - first.getTime()) / DAY - 3 + ((first.getUTCDay() + 6) % 7)) / 7);
}

/**
 * NAV series for a chart range (V2 NAV-003). Default windows keep the panel
 * readable — daily 90 days, weekly 26 weeks, monthly 12 months, quarterly 8
 * quarters, yearly all years; `extended` (the expanded chart, V2 NAV-005)
 * returns the full history: 365 days and every week, month and quarter.
 */
export function navSeries(range: NavRange, points: { q: string; nav: number; benchmark: number }[] = NAV_SERIES, extended = false): NavPoint[] {
  const day = dailyNav(points);
  const fmt = (t: number) => {
    const d = new Date(t);
    return { day: d.getUTCDate(), mon: MON[d.getUTCMonth()], year: d.getUTCFullYear() };
  };
  const pt = (p: { t: number; nav: number; benchmark: number }, label: string, title?: string): NavPoint => {
    const d = fmt(p.t);
    return { date: new Date(p.t).toISOString().slice(0, 10), label, title: title ?? `${d.day} ${d.mon} ${d.year}`, nav: p.nav, benchmark: p.benchmark };
  };
  const end = day.length - 1;
  if (range === "daily") return day.slice(end - (extended ? 365 : 90)).map((p) => pt(p, `${fmt(p.t).day} ${fmt(p.t).mon}`));
  if (range === "weekly") {
    const n = extended ? Math.floor(end / 7) : 26;
    return Array.from({ length: n + 1 }, (_, i) => day[end - (n - i) * 7]).map((p) => {
      const d = fmt(p.t);
      return pt(p, `W${isoWeek(p.t)} ’${String(d.year).slice(2)}`, `Week ${isoWeek(p.t)} · ending ${d.day} ${d.mon} ${d.year}`);
    });
  }
  if (range === "monthly") {
    const ends = day.filter((p, i) => i === end || new Date(p.t + DAY).getUTCDate() === 1);
    return ends.slice(extended ? 0 : -12).map((p) => pt(p, `${fmt(p.t).mon} ’${String(fmt(p.t).year).slice(2)}`, `${fmt(p.t).mon} ${fmt(p.t).year} month-end`));
  }
  if (range === "yearly") {
    const ends = day.filter((p, i) => i === end || (new Date(p.t).getUTCMonth() === 11 && new Date(p.t).getUTCDate() === 31));
    return ends.map((p, i) => pt(p, i === ends.length - 1 ? `${fmt(p.t).year} YTD` : `${fmt(p.t).year}`, i === ends.length - 1 ? `${fmt(p.t).year} year to date` : `${fmt(p.t).year} year-end`));
  }
  const qs = points.map((p, i) => ({ ...pt({ t: Date.parse(QUARTER_END[i]), nav: p.nav, benchmark: p.benchmark }, p.q), title: p.q }));
  return extended ? qs : qs.slice(-8);
}

export const RANGE_DELTA: Record<NavRange, string> = { daily: "DoD", weekly: "WoW", monthly: "MoM", quarterly: "QoQ", yearly: "YoY" };

/** Per-fund NAV path ($M) ending at each fund's current NAV. */
export function fundNavSeries(f: Fund) {
  const growth = f.netIrr / 100 / 4;
  const out: { q: string; nav: number }[] = [];
  let v = f.nav / 1e6;
  for (let i = QUARTERS.length - 1; i >= 0; i--) {
    out.unshift({ q: QUARTERS[i], nav: Math.round(v * 10) / 10 });
    v = v / (1 + growth + Math.sin(i * 1.7 + f.vintage) * 0.012);
  }
  return out;
}

/** Quarterly capital calls vs distributions ($M). */
export const CASH_FLOWS = [
  { q: "Q4 25", calls: 38, dists: 21 },
  { q: "Q1 26", calls: 44, dists: 18 },
  { q: "Q2 26", calls: 29, dists: 26 },
  { q: "Q3 26", calls: 42, dists: 31.8 },
];

export function fundCashFlows(f: Fund) {
  const scale = f.called / 644.8e6;
  return QUARTERS.slice(-8).map((q, i) => ({
    q,
    calls: Math.round((18 + ((i * 7 + f.vintage) % 13)) * scale * 10) / 10,
    dists: Math.round((f.distributions > 0 ? 6 + ((i * 5 + f.vintage) % 11) : 0) * scale * 10) / 10,
  }));
}

/** Q3 value bridge for one fund ($M): opening + calls − distributions + valuation + FX = closing. */
export function fundBridge(f: Fund) {
  const nav = fundNavSeries(f);
  const flows = fundCashFlows(f);
  const opening = nav[nav.length - 2].nav;
  const closing = Math.round(f.nav / 1e5) / 10;
  const calls = flows[flows.length - 1].calls;
  const dists = flows[flows.length - 1].dists;
  const fx = -Math.round(closing * 0.003 * 10) / 10;
  const valuation = Math.round((closing - opening - calls + dists - fx) * 10) / 10;
  return [
    { label: "Opening", short: "Open", value: opening, kind: "total" as const },
    { label: "Calls", value: calls, kind: "step" as const },
    { label: "Distributions", short: "Dists.", value: -dists, kind: "step" as const },
    { label: "Valuation", short: "Value", value: valuation, kind: "step" as const },
    { label: "FX", value: fx, kind: "step" as const },
    { label: "Closing", short: "Close", value: closing, kind: "total" as const },
  ];
}

/** Q3 value-creation bridge ($M): 787.0 + 42.0 − 31.8 + 17.4 − 2.2 = 812.4 */
export const BRIDGE = [
  { label: "Opening NAV", short: "Open", value: 787.0, kind: "total" as const },
  { label: "Capital calls", short: "Calls", value: 42.0, kind: "step" as const },
  { label: "Distributions", short: "Dists.", value: -31.8, kind: "step" as const },
  { label: "Valuation", short: "Value", value: 17.4, kind: "step" as const },
  { label: "FX", value: -2.2, kind: "step" as const },
  { label: "Closing NAV", short: "Close", value: 812.4, kind: "total" as const },
];

type Slice = { key: string; value: number; share: number };

function group(by: (companyId: string, fundId: string) => string): Slice[] {
  const m = new Map<string, number>();
  for (const i of INVESTMENTS) {
    const k = by(i.companyId, i.fundId);
    m.set(k, (m.get(k) ?? 0) + i.fairValue);
  }
  const total = [...m.values()].reduce((a, b) => a + b, 0);
  return [...m.entries()].map(([key, value]) => ({ key, value, share: (value / total) * 100 })).sort((a, b) => b.value - a.value);
}

const CURRENCY: Record<string, string> = { Indonesia: "IDR", Vietnam: "VND", Philippines: "PHP", Thailand: "THB", Malaysia: "MYR", Singapore: "SGD" };

/** Reporting currency of a portfolio company, from its geography (USD otherwise). */
export const currencyOf = (companyId: string) => CURRENCY[companyById(companyId)?.geography ?? ""] ?? "USD";

export const ALLOCATION = {
  sector: group((c) => companyById(c)?.sector ?? "Other"),
  geography: group((c) => companyById(c)?.geography ?? "Other"),
  strategy: group((_, f) => FUNDS.find((x) => x.id === f)?.strategy ?? "Other"),
  fund: group((_, f) => FUNDS.find((x) => x.id === f)?.short ?? "Other"),
  currency: group((c) => currencyOf(c)),
};

/** Change in sector weight vs prior quarter (pts) — illustrative. */
export const EXPOSURE_CHANGE: Record<string, number> = {
  "Digital infrastructure": 1.8,
  Renewables: 0.9,
  Consumer: -1.4,
  Healthcare: 0.3,
  Industrials: -0.6,
  Fintech: -0.7,
  Education: 0.1,
  Technology: 0.4,
};

/** Company quarterly financials ($M) ending at the current LTM run rate. */
export function companyFinancials(companyId: string) {
  const c = companyById(companyId);
  if (!c || !c.revenue) return [];
  const qRev = c.revenue / 4 / 1e6;
  const g = c.revenueGrowth / 100 / 4;
  const margin = c.ebitda / c.revenue;
  return QUARTERS.slice(-8).map((q, i) => {
    const rev = qRev / Math.pow(1 + g, 7 - i);
    const m = margin * (1 + Math.sin(i * 1.3 + c.founded) * 0.06);
    return { q, revenue: Math.round(rev * 10) / 10, ebitda: Math.round(rev * m * 10) / 10, margin: Math.round(m * 1000) / 10 };
  });
}

/** Vintage comparison for fund pages: net IRR and TVPI by fund. */
export const VINTAGE = FUNDS.map((f) => ({ fund: f.short, vintage: f.vintage, irr: f.netIrr, tvpi: (f.distributions + f.nav) / f.called }));

export type Granularity = "daily" | "weekly" | "monthly" | "quarterly" | "yearly";

/** Window per granularity: the card shows the recent window, the expanded chart a longer one. */
const WINDOW: Record<Granularity, { card: number; expanded: number }> = {
  daily: { card: 30, expanded: 90 },
  weekly: { card: 12, expanded: 26 },
  monthly: { card: 12, expanded: 24 },
  quarterly: { card: 8, expanded: 12 },
  yearly: { card: 8, expanded: 8 },
};

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

/** Period-end dates (UTC), oldest first, ending at the data date. */
function periodEnds(g: Granularity, n: number): Date[] {
  const end = new Date(AS_OF);
  const out: Date[] = [];
  for (let k = n - 1; k >= 0; k--) {
    const d = new Date(end);
    if (g === "daily") d.setUTCDate(d.getUTCDate() - k);
    else if (g === "weekly") d.setUTCDate(d.getUTCDate() - 7 * k);
    else if (g === "monthly") d.setUTCMonth(d.getUTCMonth() - k + 1, 0);
    else if (g === "quarterly") d.setUTCMonth(d.getUTCMonth() - 3 * k + 1, 0);
    else d.setUTCFullYear(d.getUTCFullYear() - k, k === 0 ? d.getUTCMonth() : 11, k === 0 ? d.getUTCDate() : 31);
    out.push(d);
  }
  return out;
}

function periodLabel(g: Granularity, d: Date): { short: string; long: string } {
  const day = d.getUTCDate();
  const mon = MONTHS[d.getUTCMonth()];
  const yy = String(d.getUTCFullYear()).slice(2);
  const long = `${day} ${mon} ${d.getUTCFullYear()}`;
  if (g === "daily" || g === "weekly") return { short: `${day} ${mon}`, long: g === "weekly" ? `Week ending ${long}` : long };
  if (g === "monthly") return { short: `${mon} ${yy}`, long: `${mon} ${d.getUTCFullYear()}` };
  if (g === "quarterly") return { short: `Q${Math.floor(d.getUTCMonth() / 3) + 1} ${yy}`, long: `Q${Math.floor(d.getUTCMonth() / 3) + 1} ${d.getUTCFullYear()}` };
  const ytd = d.getUTCMonth() !== 11 || day !== 31;
  return { short: `${d.getUTCFullYear()}`, long: ytd ? `${d.getUTCFullYear()} year to date` : `${d.getUTCFullYear()}` };
}

/**
 * Since-inception Net IRR and TVPI for each vintage, sampled at period ends.
 * IRR follows a J-curve from the first-close dip to today's figure; TVPI
 * builds from 0.95× to today's multiple. Both land exactly on the snapshot
 * values at the data date, so the lines and the "Latest" bars agree. A fund
 * has no value (NaN) before its first close on 1 January of its vintage year.
 */
export function vintageHistory(g: Granularity, expanded = false) {
  const ends = periodEnds(g, expanded ? WINDOW[g].expanded : WINDOW[g].card);
  const asOf = new Date(AS_OF).getTime();
  const year = 365.25 * 86_400_000;
  const labels = ends.map((d) => periodLabel(g, d));
  const funds = [...FUNDS].sort((a, b) => a.vintage - b.vintage).map((f, fi) => {
    const start = Date.UTC(f.vintage, 0, 1);
    const T = (asOf - start) / year;
    const tvpiNow = (f.distributions + f.nav) / f.called;
    const tau = 1.2;
    const J = -12;
    const curve = (t: number) => (Math.exp(-t / tau) - Math.exp(-T / tau)) / (1 - Math.exp(-T / tau));
    const at = (d: Date, k: number) => {
      const t = (d.getTime() - start) / year;
      if (t < 0) return { irr: Number.NaN, tvpi: Number.NaN };
      // Small mark-to-market wobble on short periods, zero at the data date.
      const wobble = g === "daily" || g === "weekly" ? Math.sin((fi + 1) * 3.1 + k * 1.7) * 0.25 * (1 - t / T) : 0;
      return {
        irr: Math.round((f.netIrr + (J - f.netIrr) * curve(t) + wobble) * 10) / 10,
        tvpi: Math.round((0.95 + (tvpiNow - 0.95) * Math.pow(Math.min(1, t / T), 1.3)) * 100) / 100,
      };
    };
    const points = ends.map(at);
    return { fund: f.short, vintage: f.vintage, irr: points.map((p) => p.irr), tvpi: points.map((p) => p.tvpi) };
  });
  return { x: labels.map((l) => l.short), titles: labels.map((l) => l.long), funds };
}

/** Sector base volatility (% annualised) for the demo risk measure. */
const SECTOR_VOL: Record<string, number> = {
  Technology: 30, Fintech: 32, "Financial services": 22, Consumer: 19, Healthcare: 16, Education: 20,
  Industrials: 17, Agriculture: 23, Renewables: 14, Infrastructure: 11, "Digital infrastructure": 15,
};

export type RiskPoint = { id: string; companyId: string; company: string; fundId: string; fund: string; sector: string; vintage: number; strategy: string; instrument: string; risk: number; ret: number; fv: number };

/**
 * Risk vs return (V2 ANA-001). Return = gross IRR. Risk = annualised volatility
 * of quarterly marks — demo values derived from sector base volatility, a
 * deterministic per-position offset, and a 50% haircut for senior loans.
 */
export const RISK_PROFILE: RiskPoint[] = INVESTMENTS.map((i, k) => {
  const c = companyById(i.companyId)!;
  const f = FUNDS.find((x) => x.id === i.fundId)!;
  const base = SECTOR_VOL[c.sector] ?? 18;
  const offset = ((k * 37 + i.companyId.charCodeAt(6) * 13) % 9) - 4;
  const risk = Math.round((base + offset) * (i.instrument === "Senior loan" ? 0.5 : i.instrument === "Preferred equity" ? 0.8 : 1) * 10) / 10;
  return { id: i.id, companyId: c.id, company: c.name, fundId: f.id, fund: f.short, sector: c.sector, vintage: f.vintage, strategy: f.strategy, instrument: i.instrument, risk, ret: i.irr, fv: i.fairValue };
});

/** Monthly heatmap of valuation change by sector (%), last 6 months. */
export const MONTHS_6 = ["Apr", "May", "Jun", "Jul", "Aug", "Sep"];
export const SECTOR_HEAT = [...new Set(COMPANIES.filter((c) => c.status !== "Exited").map((c) => c.sector))].slice(0, 8).map((sector, si) => ({
  sector,
  values: MONTHS_6.map((_, mi) => Math.round(Math.sin(si * 2.1 + mi * 0.9) * 30) / 10),
}));

export const PORTFOLIO_AS_OF = AS_OF;
export { PORTFOLIO };
