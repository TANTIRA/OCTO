/**
 * Pure table state logic (plan §21): filtering, sorting, grouping, and view
 * serialisation. Kept free of React so it is unit-tested and so tables never
 * re-derive rows inside cells.
 */

export type Value = string | number | null | undefined;

export type ColumnLogic<T> = {
  id: string;
  header: string;
  value: (row: T) => Value;
  sortValue?: (row: T) => Value;
  facet?: boolean;
  /** Bucketed value used by the Filters drawer when the raw value is too granular (dates, ages). */
  facetValue?: (row: T) => Value;
};

const facetOf = <T,>(col: ColumnLogic<T>, r: T) => (col.facetValue ?? col.value)(r);

export type Sort = { id: string; desc: boolean };

export type ViewState = {
  query: string;
  sort: Sort[];
  facets: Record<string, string[]>;
  hidden: string[];
  order: string[];
  pinned: string[];
  groupBy: string | null;
};

export type SavedView = { id: string; name: string; state: Partial<ViewState>; builtIn?: boolean };

export const EMPTY_FACET = "—";

export function compare(a: Value, b: Value): number {
  if (a == null && b == null) return 0;
  if (a == null) return 1;
  if (b == null) return -1;
  return typeof a === "number" && typeof b === "number" ? a - b : String(a).localeCompare(String(b), undefined, { numeric: true, sensitivity: "base" });
}

export function facetKey(v: Value): string {
  return v == null || v === "" ? EMPTY_FACET : String(v);
}

/** Global text search across all columns, AND-ed with per-column facet selections. */
export function filterRows<T>(rows: T[], columns: ColumnLogic<T>[], query: string, facets: Record<string, string[]>): T[] {
  const q = query.trim().toLowerCase();
  const active = Object.entries(facets).filter(([, v]) => v.length);
  const byId = new Map(columns.map((c) => [c.id, c]));
  return rows.filter((r) => {
    for (const [id, vals] of active) {
      const col = byId.get(id);
      if (col && !vals.includes(facetKey(facetOf(col, r)))) return false;
    }
    if (!q) return true;
    return columns.some((c) => String(c.value(r) ?? "").toLowerCase().includes(q));
  });
}

/** Stable multi-column sort; earlier entries in `sort` take precedence. */
export function sortRows<T>(rows: T[], columns: ColumnLogic<T>[], sort: Sort[]): T[] {
  if (!sort.length) return rows;
  const byId = new Map(columns.map((c) => [c.id, c]));
  return rows
    .map((r, i) => ({ r, i }))
    .sort((a, b) => {
      for (const s of sort) {
        const col = byId.get(s.id);
        if (!col) continue;
        const key = col.sortValue ?? col.value;
        const d = compare(key(a.r), key(b.r));
        if (d) return s.desc ? -d : d;
      }
      return a.i - b.i;
    })
    .map((x) => x.r);
}

/** Click cycles asc → desc → off. Shift-click adds or updates a secondary sort. */
export function nextSort(cur: Sort[], id: string, multi: boolean): Sort[] {
  const found = cur.find((s) => s.id === id);
  const next = !found ? { id, desc: false } : !found.desc ? { id, desc: true } : null;
  if (multi) return next ? (found ? cur.map((s) => (s.id === id ? next : s)) : [...cur, next]) : cur.filter((s) => s.id !== id);
  return next ? [next] : [];
}

export type Group<T> = { key: string; rows: T[] };

/** Groups rows by a column's value, preserving the incoming (sorted) order inside each group. */
export function groupRows<T>(rows: T[], col: ColumnLogic<T> | undefined): Group<T>[] {
  if (!col) return [{ key: "", rows }];
  const m = new Map<string, T[]>();
  for (const r of rows) {
    const k = facetKey(facetOf(col, r));
    if (!m.has(k)) m.set(k, []);
    m.get(k)!.push(r);
  }
  return [...m.entries()].map(([key, rs]) => ({ key, rows: rs }));
}

/** Distinct facet values with counts, alphabetical. */
export function facetOptions<T>(rows: T[], col: ColumnLogic<T>): [string, number][] {
  const m = new Map<string, number>();
  for (const r of rows) {
    const k = facetKey(facetOf(col, r));
    m.set(k, (m.get(k) ?? 0) + 1);
  }
  return [...m.entries()].sort((a, b) => compare(a[0], b[0]));
}

/** Column order with unknown ids appended, so a saved view survives new columns. */
export function orderColumns<C extends { id: string }>(columns: C[], order: string[], pinned: string[]): C[] {
  const rank = new Map(order.map((id, i) => [id, i]));
  const sorted = [...columns].sort((a, b) => (rank.get(a.id) ?? 999 + columns.indexOf(a)) - (rank.get(b.id) ?? 999 + columns.indexOf(b)));
  // Pinned columns always lead, in their current order.
  return [...sorted.filter((c) => pinned.includes(c.id)), ...sorted.filter((c) => !pinned.includes(c.id))];
}

/** URL-safe view encoding for shareable links. */
export function encodeView(v: ViewState): string {
  const json = JSON.stringify(v);
  const b64 = typeof btoa === "function" ? btoa(unescape(encodeURIComponent(json))) : Buffer.from(json, "utf8").toString("base64");
  return b64.replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function decodeView(s: string): ViewState | null {
  try {
    const b64 = s.replace(/-/g, "+").replace(/_/g, "/");
    const json = typeof atob === "function" ? decodeURIComponent(escape(atob(b64))) : Buffer.from(b64, "base64").toString("utf8");
    const v = JSON.parse(json) as Partial<ViewState>;
    if (typeof v !== "object" || v === null) return null;
    return {
      query: typeof v.query === "string" ? v.query : "",
      sort: Array.isArray(v.sort) ? v.sort.filter((x) => x && typeof x.id === "string").map((x) => ({ id: x.id, desc: !!x.desc })) : [],
      facets: v.facets && typeof v.facets === "object" ? Object.fromEntries(Object.entries(v.facets).filter(([, a]) => Array.isArray(a)).map(([k, a]) => [k, (a as unknown[]).map(String)])) : {},
      hidden: Array.isArray(v.hidden) ? v.hidden.map(String) : [],
      order: Array.isArray(v.order) ? v.order.map(String) : [],
      pinned: Array.isArray(v.pinned) ? v.pinned.map(String) : [],
      groupBy: typeof v.groupBy === "string" ? v.groupBy : null,
    };
  } catch {
    return null;
  }
}

export function toCsv(head: string[], rows: Value[][]): string {
  const cell = (v: Value) => {
    const s = v == null ? "" : String(v);
    return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
  };
  return [head.map(cell).join(","), ...rows.map((r) => r.map(cell).join(","))].join("\n");
}
