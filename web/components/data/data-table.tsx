"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useVirtualizer } from "@tanstack/react-virtual";
import { ArrowDown, ArrowUp, ChevronRight, Download, Layers, MoreHorizontal, WifiOff, X } from "lucide-react";
import { cn } from "@/lib/utils";
import { ROW_HEIGHT, ROW_PX, usePreferences } from "@/lib/preferences";
import { Button, IconButton, ringInset } from "@/components/ui/button";
import { Checkbox, FilterChip, SearchInput, Select } from "@/components/ui/controls";
import { Menu, type MenuItem } from "@/components/ui/overlay";
import { EmptyState, ErrorState, InlineAlert, Skeleton, useToast } from "@/components/feedback";
import { ColumnManager, DensityToggle, Pagination, SavedViewPicker } from "./table-controls";
import { FilterButton, FilterDrawer } from "./filters";

const SESSION_KEY = (id: string) => `octo.table.${id}`;
function readSessionView(id: string): Partial<ViewState> | null {
  try {
    const raw = window.sessionStorage.getItem(SESSION_KEY(id));
    return raw ? (JSON.parse(raw) as Partial<ViewState>) : null;
  } catch {
    return null;
  }
}
function writeSessionView(id: string, v: ViewState) {
  try {
    window.sessionStorage.setItem(SESSION_KEY(id), JSON.stringify({ query: v.query, facets: v.facets, sort: v.sort, groupBy: v.groupBy }));
  } catch {
    /* storage blocked — the view still works for this visit */
  }
}
import { decodeView, encodeView, facetOptions, filterRows, groupRows, nextSort, orderColumns, sortRows, toCsv, type ColumnLogic, type SavedView, type ViewState } from "./table-state";

/**
 * Column kinds drive the global alignment matrix (TABLE-001): names, entities,
 * descriptions and statuses read left; currency, percent, multiple, date, count
 * and actions align right; inline trends centre. `align` still overrides.
 */
export type ColumnKind = "name" | "entity" | "text" | "status" | "currency" | "percent" | "multiple" | "date" | "count" | "action" | "trend";

const KIND_ALIGN: Record<ColumnKind, "left" | "right" | "center"> = {
  name: "left",
  entity: "left",
  text: "left",
  status: "left",
  currency: "right",
  percent: "right",
  multiple: "right",
  date: "right",
  count: "right",
  action: "right",
  trend: "center",
};

export function alignOf(c: { align?: "left" | "right" | "center"; kind?: ColumnKind }) {
  return c.align ?? (c.kind ? KIND_ALIGN[c.kind] : "left");
}

export type Column<T> = ColumnLogic<T> & {
  cell?: (row: T) => React.ReactNode;
  kind?: ColumnKind;
  align?: "left" | "right" | "center";
  /** Pixel or percent width. Pixel widths also set sticky offsets of pinned columns; when every visible column declares a width the table uses a fixed layout so columns never shift. */
  width?: number | `${number}%`;
  hideable?: boolean;
  defaultHidden?: boolean;
  groupable?: boolean;
  sortable?: boolean;
  /** Group header / totals content for this column (e.g. Σ fair value). */
  aggregate?: (rows: T[]) => React.ReactNode;
  headerTitle?: string;
};

export type DataTableProps<T> = {
  /** Stable id for saved views and share links. */
  id: string;
  label: string;
  data: T[];
  columns: Column<T>[];
  rowId: (row: T) => string;
  status?: "ready" | "loading" | "partial" | "error";
  error?: { title: string; scope: string; reference?: string };
  onRetry?: () => void;
  staleNotice?: string;
  offline?: boolean;
  demo?: boolean;
  /** Some rows are hidden by permissions. Never say how many (plan §31). */
  restricted?: boolean;
  empty: { title: string; body: string; action?: React.ReactNode };
  views?: SavedView[];
  initial?: Partial<ViewState>;
  /**
   * Apply a view from outside the table (e.g. a KPI drawer’s "sort by this
   * metric"). Each new `key` applies `state` once and scrolls the table into view.
   */
  command?: { key: number; state: Partial<ViewState> };
  searchPlaceholder?: string;
  /** Hide the table search when the page already owns one (e.g. Deals across views). */
  search?: boolean;
  toolbar?: React.ReactNode;
  selectable?: boolean;
  bulkActions?: (rows: T[], clear: () => void) => React.ReactNode;
  rowActions?: (row: T) => (MenuItem | "separator")[];
  renderExpanded?: (row: T) => React.ReactNode;
  onRowOpen?: (row: T) => void;
  activeRowId?: string | null;
  exportName?: string;
  pageSize?: number;
  /** Virtualise when rows exceed this count (ignored with pagination). */
  virtualizeAbove?: number;
  maxHeight?: number;
  totals?: boolean;
  /** minimal = summary tables inside panels: no search, filters, views or column tools. */
  chrome?: "full" | "minimal";
  className?: string;
};

type Item<T> = { kind: "group"; key: string; rows: T[] } | { kind: "row"; row: T } | { kind: "expanded"; row: T };



function readSavedViews(id: string): SavedView[] {
  try {
    return JSON.parse(window.localStorage.getItem(`octo.views.${id}`) ?? "[]") as SavedView[];
  } catch {
    return [];
  }
}

function writeSavedViews(id: string, views: SavedView[]) {
  try {
    window.localStorage.setItem(`octo.views.${id}`, JSON.stringify(views));
  } catch {
    /* storage blocked — views last for this session */
  }
}

const toggleIn = (set: Set<string>, key: string) => {
  const n = new Set(set);
  if (n.has(key)) n.delete(key);
  else n.add(key);
  return n;
};

/**
 * Enterprise data grid (plan §21). One implementation for every table:
 * single/multi sort, text + faceted filters with removable chips, column
 * visibility/order/pinning, grouping with aggregates, expansion, selection with
 * a sticky bulk-action bar, row actions, keyboard navigation, virtualisation or
 * pagination, CSV export, saved and shareable views, global density, and
 * loading / partial / empty / filtered-empty / error / permission / stale /
 * demo / offline states.
 */
export function DataTable<T>(props: DataTableProps<T>) {
  const {
    id,
    label,
    data,
    columns,
    rowId,
    status = "ready",
    error,
    onRetry,
    staleNotice,
    offline,
    demo,
    restricted,
    empty,
    views: builtInViews = [],
    initial,
    command,
    searchPlaceholder = "Search…",
    toolbar,
    selectable,
    bulkActions,
    rowActions,
    renderExpanded,
    onRowOpen,
    activeRowId,
    exportName,
    pageSize,
    virtualizeAbove = 120,
    maxHeight = 640,
    totals,
    chrome = "full",
    search = true,
    className,
  } = props;
  const { density } = usePreferences();
  const toast = useToast();

  const [defaults] = useState<ViewState>(() => ({
    query: "",
    sort: [],
    facets: {},
    hidden: columns.filter((c) => c.defaultHidden).map((c) => c.id),
    order: columns.map((c) => c.id),
    pinned: columns.length ? [columns[0].id] : [],
    groupBy: null,
    ...initial,
  }));
  const [view, setView] = useState<ViewState>(defaults);
  const [activeView, setActiveView] = useState<string | null>(builtInViews[0]?.id ?? null);
  const [custom, setCustom] = useState<SavedView[]>([]);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());
  const [page, setPage] = useState(0);
  const [focusId, setFocusId] = useState<string | null>(null);
  const [filtersOpen, setFiltersOpen] = useState(false);
  const scrollRef = useRef<HTMLDivElement>(null);
  const bodyRef = useRef<HTMLTableSectionElement>(null);

  // Saved views from storage, then a shared view from the URL (?<id>=…) wins.
  useEffect(() => {
    setCustom(readSavedViews(id));
    const shared = new URLSearchParams(window.location.search).get(id);
    const decoded = shared ? decodeView(shared) : null;
    const session = readSessionView(id);
    if (decoded) {
      setView(decoded);
      setActiveView(null);
    } else if (session) {
      // V3 FILTER-003: returning from a detail page restores search, filters, sort and grouping.
      setView({ ...defaults, ...session });
      setActiveView(null);
    } else if (builtInViews[0]) {
      setView({ ...defaults, ...builtInViews[0].state });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id]);

  useEffect(() => {
    if (!command) return;
    setView((v) => {
      const next = { ...v, query: "", facets: {}, groupBy: null, ...command.state };
      writeSessionView(id, next);
      return next;
    });
    setActiveView(null);
    setPage(0);
    const reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    // Align the table's top (header row included) with the top of the scrolling page.
    scrollRef.current?.scrollIntoView({ block: "start", behavior: reduce ? "auto" : "smooth" });
    // Only a new command key applies; the state object is read with it.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [command?.key]);

  const patch = (p: Partial<ViewState>) => {
    setView((v) => {
      const next = { ...v, ...p };
      writeSessionView(id, next);
      return next;
    });
    setPage(0);
  };

  const allViews: SavedView[] = [...builtInViews.map((v) => ({ ...v, builtIn: true })), ...custom];
  const activeState = allViews.find((v) => v.id === activeView)?.state;
  const dirty = activeState ? JSON.stringify({ ...defaults, ...activeState }) !== JSON.stringify(view) : false;

  const ordered = orderColumns(columns, view.order, view.pinned);
  const visible = ordered.filter((c) => !view.hidden.includes(c.id));
  const byId = new Map(columns.map((c) => [c.id, c]));

  const rows = useMemo(() => sortRows(filterRows(data, columns, view.query, view.facets), columns, view.sort), [data, columns, view.query, view.facets, view.sort]);
  const pageCount = pageSize ? Math.max(1, Math.ceil(rows.length / pageSize)) : 1;
  const pageRows = pageSize ? rows.slice(page * pageSize, (page + 1) * pageSize) : rows;
  const groups = groupRows(pageRows, view.groupBy ? byId.get(view.groupBy) : undefined);

  const items: Item<T>[] = [];
  for (const g of groups) {
    if (view.groupBy) items.push({ kind: "group", key: g.key, rows: g.rows });
    if (view.groupBy && collapsed.has(g.key)) continue;
    for (const r of g.rows) {
      items.push({ kind: "row", row: r });
      if (renderExpanded && expanded.has(rowId(r))) items.push({ kind: "expanded", row: r });
    }
  }

  const virtual = !pageSize && rows.length > virtualizeAbove;
  const rowPx = ROW_PX[density];
  const virtualizer = useVirtualizer({
    count: virtual ? items.length : 0,
    getScrollElement: () => scrollRef.current,
    estimateSize: (i) => (items[i]?.kind === "group" ? 36 : items[i]?.kind === "expanded" ? 72 : rowPx),
    overscan: 10,
  });
  const vItems = virtual ? virtualizer.getVirtualItems() : [];
  const renderList = virtual ? vItems.map((v) => ({ item: items[v.index], index: v.index })) : items.map((item, index) => ({ item, index }));
  const padTop = virtual && vItems.length ? vItems[0].start : 0;
  const padBottom = virtual && vItems.length ? virtualizer.getTotalSize() - vItems[vItems.length - 1].end : 0;

  // Sticky offsets for leading pinned columns, from declared widths.
  const lefts = new Map<string, number>();
  {
    let acc = 0;
    for (const c of visible) {
      if (!view.pinned.includes(c.id)) break;
      lefts.set(c.id, acc);
      acc += typeof c.width === "number" ? c.width : 220;
    }
  }
  const lastPinned = [...lefts.keys()].pop();

  const selectedRows = data.filter((r) => selected.has(rowId(r)));
  const allSelected = rows.length > 0 && rows.every((r) => selected.has(rowId(r)));
  const someSelected = rows.some((r) => selected.has(rowId(r)));
  const facetCols = columns.filter((c) => c.facet);
  const groupCols = columns.filter((c) => c.groupable);
  const activeFacets = Object.entries(view.facets).filter(([, v]) => v.length);
  const filtering = view.query.trim() !== "" || activeFacets.length > 0;

  const exportCsv = () => {
    const source = selectedRows.length ? rows.filter((r) => selected.has(rowId(r))) : rows;
    const csv = toCsv(
      visible.map((c) => c.header),
      source.map((r) => visible.map((c) => c.value(r))),
    );
    const url = URL.createObjectURL(new Blob([csv], { type: "text/csv;charset=utf-8" }));
    Object.assign(document.createElement("a"), { href: url, download: `${exportName ?? id}.csv` }).click();
    URL.revokeObjectURL(url);
    toast({ tone: "ok", title: `Exported ${source.length} rows`, body: selectedRows.length ? "Selected rows only." : "All rows matching the current filters." });
  };

  const share = () => {
    const url = new URL(window.location.href);
    url.searchParams.set(id, encodeView(view));
    const done = () => toast({ tone: "ok", title: "Link copied", body: "It opens this exact view: filters, sort, columns and grouping." });
    if (navigator.clipboard) navigator.clipboard.writeText(url.toString()).then(done, () => toast({ tone: "danger", title: "Couldn’t copy the link" }));
    else toast({ tone: "danger", title: "Clipboard unavailable" });
  };

  const saveView = (name: string) => {
    const v: SavedView = { id: `custom-${Date.now()}`, name, state: view };
    const next = [...custom, v];
    setCustom(next);
    setActiveView(v.id);
    writeSavedViews(id, next);
    toast({ tone: "ok", title: `Saved view “${name}”` });
  };

  const deleteView = (vid: string) => {
    const next = custom.filter((v) => v.id !== vid);
    setCustom(next);
    if (activeView === vid) setActiveView(null);
    writeSavedViews(id, next);
  };

  const moveColumn = (cid: string, dir: -1 | 1) => {
    const order = ordered.map((c) => c.id);
    const i = order.indexOf(cid);
    const j = i + dir;
    if (j < 0 || j >= order.length) return;
    [order[i], order[j]] = [order[j], order[i]];
    patch({ order });
  };

  // Keyboard (roving tabindex): ↑/↓ Home/End move, Enter opens, Space selects, →/← expand.
  const focusRow = useCallback((rid: string) => {
    setFocusId(rid);
    requestAnimationFrame(() => bodyRef.current?.querySelector<HTMLElement>(`tr[data-row-id="${CSS.escape(rid)}"]`)?.focus());
  }, []);
  const rowOrder = items.flatMap((it) => (it.kind === "row" ? [rowId(it.row)] : []));
  const onRowKey = (e: React.KeyboardEvent, row: T) => {
    if (e.target !== e.currentTarget) return;
    const rid = rowId(row);
    const i = rowOrder.indexOf(rid);
    const go = (n: number) => {
      const target = rowOrder[Math.max(0, Math.min(rowOrder.length - 1, n))];
      if (!target) return;
      if (virtual) virtualizer.scrollToIndex(items.findIndex((it) => it.kind === "row" && rowId(it.row) === target), { align: "auto" });
      focusRow(target);
    };
    if (e.key === "ArrowDown") (e.preventDefault(), go(i + 1));
    else if (e.key === "ArrowUp") (e.preventDefault(), go(i - 1));
    else if (e.key === "Home") (e.preventDefault(), go(0));
    else if (e.key === "End") (e.preventDefault(), go(rowOrder.length - 1));
    else if (e.key === "Enter" && onRowOpen) (e.preventDefault(), onRowOpen(row));
    else if (e.key === " " && selectable) (e.preventDefault(), setSelected((s) => toggleIn(s, rid)));
    else if (e.key === "ArrowRight" && renderExpanded) setExpanded((s) => new Set(s).add(rid));
    else if (e.key === "ArrowLeft" && renderExpanded) setExpanded((s) => (s.delete(rid), new Set(s)));
  };
  const tabRow = focusId && rowOrder.includes(focusId) ? focusId : rowOrder[0];

  const rowH = ROW_HEIGHT[density];
  const colSpan = visible.length + (rowActions ? 1 : 0);
  const pinStyle = (cid: string): React.CSSProperties => {
    const w = byId.get(cid)?.width;
    return lefts.has(cid) ? { left: lefts.get(cid), width: w, minWidth: w } : { width: w };
  };
  const pinCls = (cid: string) => (lefts.has(cid) ? cn("sticky z-[2]", cid === lastPinned && "shadow-[inset_-1px_0_0_var(--color-line)]") : "");

  const cellContent = (c: Column<T>, r: T, ci: number) => {
    const rid = rowId(r);
    return (
      <span className={cn("flex min-w-0 items-center gap-2", alignOf(c) === "right" && "justify-end", alignOf(c) === "center" && "justify-center")}>
        {ci === 0 && selectable && <Checkbox label={`Select ${rid}`} checked={selected.has(rid)} onChange={() => setSelected((s) => toggleIn(s, rid))} />}
        {ci === 0 && renderExpanded && (
          <button
            type="button"
            tabIndex={-1}
            aria-label={expanded.has(rid) ? `Collapse ${rid}` : `Expand ${rid}`}
            aria-expanded={expanded.has(rid)}
            onClick={() => setExpanded((s) => toggleIn(s, rid))}
            className={cn("flex size-5 shrink-0 cursor-pointer items-center justify-center rounded-sm text-ink-3 hover:bg-hover hover:text-ink", ringInset)}
          >
            <ChevronRight aria-hidden className={cn("size-3.5 transition-transform duration-150", expanded.has(rid) && "rotate-90")} />
          </button>
        )}
        <span className={cn("min-w-0", alignOf(c) === "right" ? "tabular-nums" : "truncate")}>{c.cell ? c.cell(r) : (c.value(r) ?? "—")}</span>
      </span>
    );
  };

  return (
    <div className={cn("relative flex min-w-0 flex-col bg-surface", chrome === "full" ? "rounded-lg border border-line" : "", className)}>
      {/* Toolbar: search · filters · group · views · columns · density · export */}
      {chrome === "full" && (
      <div className="flex flex-wrap items-center gap-2 border-b border-line px-3 py-2.5">
        {search && <SearchInput aria-label={`Search ${label}`} placeholder={searchPlaceholder} value={view.query} onChange={(e) => patch({ query: e.target.value })} className="w-full sm:w-60" />}
        {facetCols.length > 0 && <FilterButton count={activeFacets.length} open={filtersOpen} onClick={() => setFiltersOpen(true)} />}
        {groupCols.length > 0 && (
          <label className="flex items-center gap-1.5 text-[12px] text-ink-3">
            <Layers aria-hidden className="size-3.5" />
            <Select value={view.groupBy ?? ""} onChange={(e) => patch({ groupBy: e.target.value || null })} className="w-40 [&_select]:text-[12px]" aria-label="Group rows by">
              <option value="">No grouping</option>
              {groupCols.map((c) => (
                <option key={c.id} value={c.id}>
                  Group by {c.header.toLowerCase()}
                </option>
              ))}
            </Select>
          </label>
        )}
        <div className="ml-auto flex flex-wrap items-center gap-2">
          {toolbar}
          <SavedViewPicker
            views={allViews}
            activeId={activeView}
            dirty={dirty}
            onSelect={(v) => {
              setView({ ...defaults, ...v.state });
              setActiveView(v.id);
              setPage(0);
            }}
            onSave={saveView}
            onDelete={deleteView}
            onShare={share}
          />
          <ColumnManager
            columns={ordered}
            hidden={view.hidden}
            pinned={view.pinned}
            onToggle={(cid) => patch({ hidden: view.hidden.includes(cid) ? view.hidden.filter((x) => x !== cid) : [...view.hidden, cid] })}
            onMove={moveColumn}
            onPin={(cid) => patch({ pinned: view.pinned.includes(cid) ? view.pinned.filter((x) => x !== cid) : [...view.pinned, cid] })}
            onReset={() => patch({ hidden: defaults.hidden, order: defaults.order, pinned: defaults.pinned })}
          />
          <DensityToggle />
          <Button size="sm" onClick={exportCsv} disabled={status === "loading" || rows.length === 0}>
            <Download /> <span className="hidden sm:inline">Export</span>
          </Button>
        </div>
      </div>
      )}

      <FilterDrawer
        open={filtersOpen}
        onClose={() => setFiltersOpen(false)}
        title={`Filter ${label.toLowerCase()}`}
        fields={facetCols.map((c) => ({ id: c.id, label: c.header, options: facetOptions(data, c) }))}
        value={view.facets}
        onApply={(facets) => patch({ facets })}
      />

      {/* Applied filters with scope count (plan §22: removable, counted, persisted in the view) */}
      {filtering && (
        <div className="flex flex-wrap items-center gap-1.5 border-b border-line px-3 py-2">
          {view.query.trim() && <FilterChip label="Search" value={view.query} onRemove={() => patch({ query: "" })} />}
          {activeFacets.flatMap(([cid, vals]) =>
            vals.map((v) => <FilterChip key={`${cid}-${v}`} label={byId.get(cid)?.header ?? cid} value={v} onRemove={() => patch({ facets: { ...view.facets, [cid]: vals.filter((x) => x !== v) } })} />),
          )}
          <span className="ml-1 text-[12px] tabular-nums text-ink-3">
            {rows.length} of {data.length}
          </span>
          <Button size="xs" variant="ghost" onClick={() => patch({ query: "", facets: {} })}>
            <X /> Clear all
          </Button>
        </div>
      )}

      {(offline || staleNotice || restricted || status === "partial") && (
        <div className="space-y-2 border-b border-line px-3 py-2">
          {offline && (
            <InlineAlert tone="warn" title="You’re offline">
              <span className="inline-flex items-center gap-1">
                <WifiOff aria-hidden className="size-3" /> Showing the last loaded rows. Changes are disabled until you reconnect.
              </span>
            </InlineAlert>
          )}
          {staleNotice && <InlineAlert tone="warn">{staleNotice}</InlineAlert>}
          {restricted && <InlineAlert tone="restricted">Some rows are hidden by your permissions.</InlineAlert>}
          {status === "partial" && <InlineAlert tone="info">Loading the remaining rows… sorting and totals update when complete.</InlineAlert>}
        </div>
      )}

      {status === "error" ? (
        <ErrorState title={error?.title ?? `Couldn’t load ${label.toLowerCase()}`} scope={error?.scope ?? "The rest of the page still works."} reference={error?.reference} onRetry={onRetry} />
      ) : status !== "loading" && data.length === 0 ? (
        <EmptyState title={empty.title} body={empty.body} action={empty.action} />
      ) : (
        <div ref={scrollRef} className="relative min-h-0 overflow-auto overscroll-x-contain" style={virtual ? { maxHeight } : undefined}>
          <table className={cn("w-full border-separate border-spacing-0 text-[13px]", visible.every((c) => c.width !== undefined) && "min-w-[720px] table-fixed")} aria-label={label} aria-busy={status === "loading" || status === "partial"} aria-rowcount={rows.length + 1}>
            <thead className="sticky top-0 z-20">
              <tr>
                {visible.map((c, ci) => {
                  const s = view.sort.find((x) => x.id === c.id);
                  const sortable = c.sortable !== false;
                  return (
                    <th
                      key={c.id}
                      scope="col"
                      aria-sort={s ? (s.desc ? "descending" : "ascending") : sortable ? "none" : undefined}
                      style={pinStyle(c.id)}
                      title={c.headerTitle}
                      className={cn(
                        "h-10 whitespace-nowrap border-b border-line bg-head px-3 text-[11px] font-semibold uppercase tracking-[0.04em] text-ink-3",
                        alignOf(c) === "right" ? "text-right" : alignOf(c) === "center" ? "text-center" : "text-left",
                        pinCls(c.id),
                      )}
                    >
                      <span className={cn("inline-flex items-center gap-2", alignOf(c) === "right" && "flex-row-reverse")}>
                        {ci === 0 && selectable && <Checkbox label="Select all rows" checked={allSelected} indeterminate={!allSelected && someSelected} onChange={(v) => setSelected(v ? new Set(rows.map(rowId)) : new Set())} />}
                        {ci === 0 && renderExpanded && <span aria-hidden className="w-5" />}
                        {sortable ? (
                          <button
                            type="button"
                            onClick={(e) => patch({ sort: nextSort(view.sort, c.id, e.shiftKey) })}
                            title="Sort · Shift-click adds a secondary sort"
                            className={cn("inline-flex cursor-pointer items-center gap-1 rounded-sm uppercase hover:text-ink", s && "text-ink", ringInset)}
                          >
                            {c.header}
                            {s ? s.desc ? <ArrowDown aria-hidden className="size-3" /> : <ArrowUp aria-hidden className="size-3" /> : null}
                            {s && view.sort.length > 1 && <span className="text-[9px] tabular-nums text-accent">{view.sort.indexOf(s) + 1}</span>}
                          </button>
                        ) : (
                          c.header
                        )}
                      </span>
                    </th>
                  );
                })}
                {rowActions && (
                  <th scope="col" className="sticky right-0 z-[2] h-10 w-10 border-b border-line bg-head">
                    <span className="sr-only">Actions</span>
                  </th>
                )}
              </tr>
            </thead>
            <tbody ref={bodyRef}>
              {status === "loading" &&
                Array.from({ length: 8 }, (_, i) => (
                  <tr key={i} className={rowH}>
                    {visible.map((c) => (
                      <td key={c.id} className="border-b border-line px-3">
                        <Skeleton className={cn("h-3", alignOf(c) === "right" ? "ml-auto w-14" : "w-3/4")} />
                      </td>
                    ))}
                    {rowActions && <td className="border-b border-line" />}
                  </tr>
                ))}

              {status !== "loading" && rows.length === 0 && (
                <tr>
                  <td colSpan={colSpan}>
                    <EmptyState
                      title="No rows match"
                      body={`${data.length} rows are hidden by the current search or filters.`}
                      action={
                        <Button size="sm" onClick={() => patch({ query: "", facets: {} })}>
                          Clear filters
                        </Button>
                      }
                    />
                  </td>
                </tr>
              )}

              {padTop > 0 && (
                <tr aria-hidden style={{ height: padTop }}>
                  <td colSpan={colSpan} />
                </tr>
              )}

              {status !== "loading" &&
                renderList.map(({ item, index }) => {
                  if (item.kind === "group") {
                    const isCollapsed = collapsed.has(item.key);
                    return (
                      <tr key={`g-${item.key}`} data-index={index}>
                        {visible.map((c, ci) => (
                          <td key={c.id} style={pinStyle(c.id)} className={cn("h-9 border-b border-line bg-subtle px-3 text-[12px]", alignOf(c) === "right" && "text-right tabular-nums", pinCls(c.id))}>
                            {ci === 0 ? (
                              <button type="button" aria-expanded={!isCollapsed} onClick={() => setCollapsed((s) => toggleIn(s, item.key))} className={cn("inline-flex cursor-pointer items-center gap-1.5 rounded-sm font-semibold text-ink", ringInset)}>
                                <ChevronRight aria-hidden className={cn("size-3.5 text-ink-3 transition-transform duration-150", !isCollapsed && "rotate-90")} />
                                {item.key}
                                <span className="font-normal tabular-nums text-ink-3">{item.rows.length}</span>
                              </button>
                            ) : (
                              <span className="font-medium text-ink-2">{c.aggregate?.(item.rows)}</span>
                            )}
                          </td>
                        ))}
                        {rowActions && <td className="sticky right-0 border-b border-line bg-subtle" />}
                      </tr>
                    );
                  }
                  if (item.kind === "expanded") {
                    return (
                      <tr key={`x-${rowId(item.row)}`} data-index={index}>
                        <td colSpan={colSpan} className="border-b border-line bg-subtle px-3 py-3 pl-14">
                          {renderExpanded?.(item.row)}
                        </td>
                      </tr>
                    );
                  }
                  const r = item.row;
                  const rid = rowId(r);
                  const isSel = selected.has(rid);
                  const isActive = activeRowId === rid;
                  const rowBg = isSel || isActive ? "bg-accent-soft" : "bg-surface group-hover/row:bg-hover group-focus-visible/row:bg-hover";
                  return (
                    <tr
                      key={rid}
                      data-row-id={rid}
                      data-index={index}
                      tabIndex={rid === tabRow ? 0 : -1}
                      aria-selected={selectable ? isSel : undefined}
                      aria-current={isActive || undefined}
                      onFocus={() => setFocusId(rid)}
                      onKeyDown={(e) => onRowKey(e, r)}
                      onClick={(e) => {
                        if ((e.target as HTMLElement).closest("button, a, input, [role=menu]")) return;
                        onRowOpen?.(r);
                      }}
                      className={cn("group/row focus-visible:outline-none", rowH, onRowOpen && "cursor-pointer")}
                    >
                      {visible.map((c, ci) => (
                        <td
                          key={c.id}
                          style={pinStyle(c.id)}
                          className={cn(
                            "whitespace-nowrap border-b border-line px-3 text-ink-2 transition-colors duration-100",
                            rowBg,
                            alignOf(c) === "right" && "text-right",
                            ci === 0 && "text-ink group-focus-visible/row:shadow-[inset_2px_0_0_var(--color-accent)]",
                            pinCls(c.id),
                          )}
                        >
                          {cellContent(c, r, ci)}
                        </td>
                      ))}
                      {rowActions && (
                        <td className={cn("sticky right-0 z-[2] border-b border-line px-1", rowBg)}>
                          <Menu
                            label={`Actions for ${rid}`}
                            items={rowActions(r)}
                            trigger={({ ref, open, toggle }) => <IconButton ref={ref} size="sm" label={`Actions for ${rid}`} aria-haspopup="menu" aria-expanded={open} icon={<MoreHorizontal />} onClick={toggle} />}
                          />
                        </td>
                      )}
                    </tr>
                  );
                })}

              {padBottom > 0 && (
                <tr aria-hidden style={{ height: padBottom }}>
                  <td colSpan={colSpan} />
                </tr>
              )}
            </tbody>
            {totals && status !== "loading" && rows.length > 0 && (
              <tfoot className="sticky bottom-0 z-20">
                <tr>
                  {visible.map((c, ci) => (
                    <td key={c.id} style={pinStyle(c.id)} className={cn("h-10 border-t border-line-strong bg-head px-3 text-[12px] font-semibold text-ink", alignOf(c) === "right" && "text-right tabular-nums", pinCls(c.id))}>
                      {ci === 0 ? `Total · ${rows.length}` : c.aggregate?.(rows)}
                    </td>
                  ))}
                  {rowActions && <td className="sticky right-0 border-t border-line-strong bg-head" />}
                </tr>
              </tfoot>
            )}
          </table>
        </div>
      )}

      {/* Sticky bulk-action bar — only after selection; a bottom sheet on phones (plan §21, §28) */}
      {selectable && selectedRows.length > 0 && (
        <div role="region" aria-label="Bulk actions" className="sticky bottom-0 z-30 flex flex-wrap items-center gap-2 rounded-b-xl border-t border-accent-line bg-accent-soft px-3 py-2 max-sm:fixed max-sm:inset-x-0 max-sm:bottom-0 max-sm:rounded-none max-sm:shadow-dialog">
          <span className="text-[13px] font-medium text-ink">{selectedRows.length} selected</span>
          {bulkActions?.(selectedRows, () => setSelected(new Set()))}
          <Button size="sm" variant="ghost" className="ml-auto" onClick={() => setSelected(new Set())}>
            Clear
          </Button>
        </div>
      )}

      {status !== "error" && data.length > 0 && chrome === "full" && (
        <div className="flex flex-wrap items-center justify-between gap-2 border-t border-line px-3 py-2 text-[12px] text-ink-3">
          <span className="flex flex-wrap items-center gap-x-2">
            <span className="tabular-nums">{rows.length === data.length ? `${data.length} rows` : `${rows.length} of ${data.length} rows`}</span>
            {view.sort.length > 1 && <span>· sorted by {view.sort.length} columns</span>}
            {view.groupBy && <span>· grouped by {byId.get(view.groupBy)?.header.toLowerCase()}</span>}
            {demo && (
              <span className="inline-flex items-center gap-1">
                · <span aria-hidden className="size-1.5 rounded-full bg-info" /> Demo data
              </span>
            )}
          </span>
          {pageSize ? (
            <Pagination page={page} pageCount={pageCount} total={rows.length} pageSize={pageSize} onPage={setPage} />
          ) : (
            <span className="hidden sm:inline">
              ↑↓ move · Enter open{selectable && " · Space select"}
              {renderExpanded && " · →← expand"}
            </span>
          )}
        </div>
      )}
    </div>
  );
}
