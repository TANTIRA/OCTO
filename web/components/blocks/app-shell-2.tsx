"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { motion, useReducedMotion } from "motion/react";
import {
  Bell,
  Building2,
  ChartPie,
  Check,
  ChevronsUpDown,
  GitCompareArrows,
  Kanban,
  Landmark,
  LayoutDashboard,
  Menu,
  X,
  type LucideIcon,
} from "lucide-react";
import Dashboard4 from "@/components/blocks/dashboard-4";
import DataTable3 from "@/components/blocks/data-table-3";
import Kanban1 from "@/components/blocks/kanban-1";
import PipelineBoard from "@/components/pipeline-board";
import ReportQueue from "@/components/report-queue";
import ReconPanel from "@/components/recon-panel";
import CompliancePanel from "@/components/compliance-panel";
import AgentRunsPanel from "@/components/agent-runs-panel";
import SessionMenu from "@/components/session-menu";
import { apiFetch } from "@/lib/api";

const cx = (...c: (string | false | null | undefined)[]) =>
  c.filter(Boolean).join(" ");

function useScrollFade<T extends HTMLElement>() {
  const ref = useRef<T>(null);
  const [edges, setEdges] = useState({ start: false, end: false });

  const update = useCallback(() => {
    const el = ref.current;
    if (!el) return;
    const { scrollTop, scrollHeight, clientHeight } = el;
    setEdges({
      start: scrollTop > 1,
      end: Math.ceil(scrollTop + clientHeight) < scrollHeight - 1,
    });
  }, []);

  useEffect(() => {
    update();
    const el = ref.current;
    const view = el?.ownerDocument.defaultView;
    if (!el || !view?.ResizeObserver) return;
    const observer = new view.ResizeObserver(update);
    observer.observe(el);
    return () => observer.disconnect();
  }, [update]);

  return { ref, edges, onScroll: update };
}

const focus =
  "focus-visible:outline-none focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--rb-accent,oklch(20.5%_0_0))] dark:focus-visible:outline-[var(--rb-accent,oklch(100%_0_0))]";

const focusInset =
  "focus-visible:outline-none focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-[var(--rb-accent,oklch(20.5%_0_0))] dark:focus-visible:outline-[var(--rb-accent,oklch(100%_0_0))]";

const transition =
  "transition-[background-color,border-color,color] duration-150 ease-out";

const EASE_OUT: [number, number, number, number] = [0.23, 1, 0.32, 1];

const TOOLTIP_DELAY_MS = 400;
const TOOLTIP_GRACE_MS = 300;

type Tone = "neutral" | "attention" | "critical";

const DOT: Record<Tone, string> = {
  neutral: "bg-neutral-300 dark:bg-neutral-600",
  attention: "bg-amber-500",
  critical: "bg-red-500",
};

type Row = {
  name: string;
  detail: string;
  meta: string;
  status: string;
  tone: Tone;
};

type Group = {
  label: string;
  items: { label: string; count?: string }[];
};

type Area = {
  id: string;
  label: string;
  icon: LucideIcon;
  current: string;
  groups: Group[];
  title: string;
  listTitle: string;
  listCount: string;
  rows: Row[];
};

const AREAS: Area[] = [
  {
    id: "overview",
    label: "Overview",
    icon: LayoutDashboard,
    current: "Fund overview",
    title: "Portfolio overview",
    listTitle: "Fund overview",
    listCount: "3 funds",
    groups: [
      {
        label: "Overview",
        items: [
          { label: "Fund overview" },
          { label: "NAV summary" },
          { label: "Exposure" },
          { label: "Cash & projections" },
        ],
      },
      {
        label: "Saved views",
        items: [
          { label: "Quarterly pack" },
          { label: "IC briefing" },
          { label: "LP report draft" },
        ],
      },
    ],
    rows: [],
  },
  {
    id: "assets",
    label: "Assets",
    icon: Landmark,
    current: "All assets",
    title: "Assets",
    listTitle: "Asset register",
    listCount: "148",
    groups: [
      {
        label: "Assets",
        items: [
          { label: "All assets", count: "148" },
          { label: "Portfolio companies", count: "32" },
          { label: "Fund interests", count: "24" },
          { label: "Listed equity", count: "58" },
          { label: "Private credit", count: "19" },
          { label: "Digital assets", count: "15" },
        ],
      },
      {
        label: "Reference",
        items: [
          { label: "Instrument master" },
          { label: "Issuers" },
          { label: "Identifiers" },
        ],
      },
    ],
    rows: [],
  },
  {
    id: "positions",
    label: "Positions",
    icon: ChartPie,
    current: "All positions",
    title: "Positions",
    listTitle: "Open positions",
    listCount: "87",
    groups: [
      {
        label: "Positions",
        items: [
          { label: "All positions", count: "87" },
          { label: "By fund" },
          { label: "By strategy" },
          { label: "By geography" },
          { label: "By sector" },
        ],
      },
      {
        label: "Saved views",
        items: [
          { label: "Top 10 by NAV" },
          { label: "Underwater" },
          { label: "Recent writes" },
          { label: "Look-through" },
        ],
      },
    ],
    rows: [
      {
        name: "Meridian Health Group",
        detail: "Fund II · Buyout · Healthcare · ID",
        meta: "$42.1m",
        status: "Mark current",
        tone: "neutral",
      },
      {
        name: "Cipta Logistics",
        detail: "Fund II · Growth · Logistics · ID",
        meta: "$28.4m",
        status: "Mark current",
        tone: "neutral",
      },
      {
        name: "Solus Energy Partners",
        detail: "Opportunities I · Private credit · SG",
        meta: "$18.7m",
        status: "Valuation stale",
        tone: "attention",
      },
      {
        name: "Aruna Payments",
        detail: "Co-Invest SPV · Fintech · SG",
        meta: "$12.3m",
        status: "Mark current",
        tone: "neutral",
      },
      {
        name: "PT Barito Renewables",
        detail: "Fund II · Listed equity · Energy · ID",
        meta: "$9.8m",
        status: "Mark current",
        tone: "neutral",
      },
      {
        name: "Helios Data Centers",
        detail: "Opportunities I · Infra · MY",
        meta: "$31.5m",
        status: "Covenant breach",
        tone: "critical",
      },
      {
        name: "Kirana Consumer Brands",
        detail: "Fund II · Buyout · Consumer · ID",
        meta: "$22.9m",
        status: "Mark current",
        tone: "neutral",
      },
      {
        name: "Anchor Re Holdings",
        detail: "Fund I · Insurance · BM",
        meta: "$15.2m",
        status: "Exiting",
        tone: "attention",
      },
    ],
  },
  {
    id: "deals",
    label: "Deals",
    icon: Kanban,
    current: "Pipeline",
    title: "Deal pipeline",
    listTitle: "Pipeline",
    listCount: "23",
    groups: [
      {
        label: "Deals",
        items: [
          { label: "Pipeline", count: "23" },
          { label: "Sourced", count: "9" },
          { label: "Screening", count: "6" },
          { label: "Due diligence", count: "5" },
          { label: "IC review", count: "3" },
          { label: "Closed" },
        ],
      },
      {
        label: "Saved views",
        items: [
          { label: "My deals" },
          { label: "Aging > 60d" },
          { label: "This quarter" },
          { label: "Passed" },
        ],
      },
    ],
    rows: [],
  },
  {
    id: "recon",
    label: "Recon",
    icon: GitCompareArrows,
    current: "Open breaks",
    title: "Reconciliation",
    listTitle: "Open breaks",
    listCount: "7",
    groups: [
      {
        label: "Recon",
        items: [
          { label: "Open breaks", count: "7" },
          { label: "Resolved" },
          { label: "Runs" },
          { label: "Rules" },
        ],
      },
      {
        label: "Sources",
        items: [
          { label: "Custodian feeds" },
          { label: "Fund admin" },
          { label: "Broker statements" },
        ],
      },
    ],
    rows: [
      {
        name: "Cash break — USD operating",
        detail: "Custodian vs IBOR · run #412",
        meta: "$184k",
        status: "Open",
        tone: "critical",
      },
      {
        name: "Position qty mismatch",
        detail: "PT Barito Renewables · admin vs ledger",
        meta: "12,400 sh",
        status: "Open",
        tone: "attention",
      },
      {
        name: "Unsettled capital call",
        detail: "Solus Energy Partners · due 12 Sep",
        meta: "$2.0m",
        status: "Open",
        tone: "attention",
      },
      {
        name: "FX revaluation drift",
        detail: "IDR/USD · mark source variance",
        meta: "0.4%",
        status: "Review",
        tone: "neutral",
      },
      {
        name: "Missing trade confirm",
        detail: "Aruna Payments follow-on",
        meta: "-",
        status: "Open",
        tone: "attention",
      },
    ],
  },
  {
    id: "reports",
    label: "Reports",
    icon: ChartPie,
    current: "Queue",
    title: "Reports",
    listTitle: "Queue",
    listCount: "live",
    groups: [
      {
        label: "Reports",
        items: [
          { label: "Queue" },
          { label: "Performance" },
          { label: "Exposure" },
          { label: "GL export" },
        ],
      },
    ],
    rows: [],
  },
  {
    id: "compliance",
    label: "Compliance",
    icon: Check,
    current: "Rules",
    title: "Compliance",
    listTitle: "Rules",
    listCount: "live",
    groups: [
      {
        label: "Compliance",
        items: [
          { label: "Rules" },
          { label: "Evaluations" },
          { label: "Breaches" },
        ],
      },
    ],
    rows: [],
  },
  {
    id: "alerts",
    label: "Alerts",
    icon: Bell,
    current: "Active alerts",
    title: "Alerts & agents",
    listTitle: "Active alerts",
    listCount: "11",
    groups: [
      {
        label: "Alerts",
        items: [
          { label: "Active", count: "11" },
          { label: "Snoozed" },
          { label: "Resolved" },
          { label: "Rules" },
        ],
      },
      {
        label: "Agents",
        items: [
          { label: "News matching" },
          { label: "NL query" },
          { label: "Email drafts" },
          { label: "Approval queue", count: "2" },
        ],
      },
    ],
    rows: [
      {
        name: "Covenant breach — Helios Data Centers",
        detail: "DSCR below 1.2x threshold · rule #18",
        meta: "2h ago",
        status: "Critical",
        tone: "critical",
      },
      {
        name: "Valuation stale — Solus Energy",
        detail: "No mark in 45 days · rule #7",
        meta: "6h ago",
        status: "Warning",
        tone: "attention",
      },
      {
        name: "News match — Meridian Health",
        detail: "Regulatory filing detected · agent: news",
        meta: "1d ago",
        status: "Info",
        tone: "neutral",
      },
      {
        name: "IC memo draft ready",
        detail: "Kirana Consumer add-on · agent: DDQ",
        meta: "1d ago",
        status: "Awaiting approval",
        tone: "attention",
      },
      {
        name: "LP report generated",
        detail: "Q3 pack · Flagship Fund II",
        meta: "2d ago",
        status: "Done",
        tone: "neutral",
      },
    ],
  },
];

function WorkspaceSwitcher({
  workspaces,
  loadFailed,
}: {
  workspaces: { name: string; members: string }[];
  loadFailed: boolean;
}) {
  const [workspace, setWorkspace] = useState(workspaces[0]?.name ?? "");
  const [open, setOpen] = useState(false);
  const [shown, setShown] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);

  // Tenants arrive async — if the current pick is no longer in the list
  // (or the list just loaded), fall back to the first entry.
  useEffect(() => {
    if (workspaces.length && !workspaces.some((w) => w.name === workspace)) {
      setWorkspace(workspaces[0].name);
    }
  }, [workspaces, workspace]);

  const close = (restoreFocus: boolean) => {
    setOpen(false);
    setShown(false);
    if (restoreFocus) triggerRef.current?.focus({ preventScroll: true });
  };

  useEffect(() => {
    if (!open) return;
    const doc = rootRef.current?.ownerDocument ?? document;
    const frame = requestAnimationFrame(() => {
      setShown(true);
      menuRef.current
        ?.querySelector<HTMLElement>('[role="menuitemradio"]')
        ?.focus({ preventScroll: true });
    });

    const onPointerDown = (event: Event) => {
      if (!rootRef.current?.contains(event.target as Node)) {
        setOpen(false);
        setShown(false);
      }
    };

    const onKeyDown = (event: KeyboardEvent) => {
      const items = Array.from(
        menuRef.current?.querySelectorAll<HTMLElement>(
          '[role="menuitemradio"], [role="menuitem"]',
        ) ?? [],
      );
      if (items.length === 0) return;
      const index = items.indexOf(doc.activeElement as HTMLElement);
      if (event.key === "Escape") {
        event.preventDefault();
        close(true);
      } else if (event.key === "ArrowDown") {
        event.preventDefault();
        items[index < 0 || index === items.length - 1 ? 0 : index + 1]?.focus({
          preventScroll: true,
        });
      } else if (event.key === "ArrowUp") {
        event.preventDefault();
        items[index <= 0 ? items.length - 1 : index - 1]?.focus({
          preventScroll: true,
        });
      } else if (event.key === "Home") {
        event.preventDefault();
        items[0]?.focus({ preventScroll: true });
      } else if (event.key === "End") {
        event.preventDefault();
        items[items.length - 1]?.focus({ preventScroll: true });
      } else if (event.key === "Tab") {
        setOpen(false);
        setShown(false);
      }
    };

    doc.addEventListener("pointerdown", onPointerDown);
    doc.addEventListener("keydown", onKeyDown);
    return () => {
      cancelAnimationFrame(frame);
      doc.removeEventListener("pointerdown", onPointerDown);
      doc.removeEventListener("keydown", onKeyDown);
    };
  }, [open]);

  const active = workspaces.find((item) => item.name === workspace);

  // An empty list is honest state, not a menu — never offer fabricated names.
  if (workspaces.length === 0) {
    return (
      <div
        className={cx(
          "flex h-11 w-full items-center gap-2 rounded-[var(--rb-r-lg,10px)] bg-neutral-100 px-2 text-left dark:bg-neutral-800",
          transition,
        )}
      >
        <span className="min-w-0 flex-1">
          <span className="block truncate text-[13px] font-medium text-neutral-900 dark:text-neutral-100">
            {loadFailed ? "Vehicles unavailable" : "No vehicles"}
          </span>
          <span className="block truncate text-xs text-neutral-500 dark:text-neutral-500">
            {loadFailed ? "couldn't reach the API" : "none assigned to you"}
          </span>
        </span>
      </div>
    );
  }

  return (
    <div ref={rootRef} className="relative">
      <button
        ref={triggerRef}
        type="button"
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => (open ? close(false) : setOpen(true))}
        className={cx(
          "flex h-11 w-full cursor-pointer items-center gap-2 rounded-[var(--rb-r-lg,10px)] bg-neutral-100 px-2 text-left hover:bg-neutral-200 active:bg-neutral-200 dark:bg-neutral-800 dark:hover:bg-neutral-700 dark:active:bg-neutral-700",
          transition,
          focus,
        )}
      >
        <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-[var(--rb-r-md,8px)] bg-neutral-300 text-xs font-medium text-neutral-700 dark:bg-neutral-600 dark:text-neutral-100">
          {workspace.charAt(0)}
        </span>
        <span className="min-w-0 flex-1">
          <span className="block truncate text-[13px] font-medium text-neutral-900 dark:text-neutral-100">
            {workspace}
          </span>
          <span className="block truncate text-xs text-neutral-500 dark:text-neutral-500">
            {active?.members}
          </span>
        </span>
        <ChevronsUpDown
          aria-hidden="true"
          className="h-4 w-4 shrink-0 text-neutral-500 dark:text-neutral-500"
        />
      </button>

      {open && (
        <div
          ref={menuRef}
          role="menu"
          aria-label="Switch vehicle"
          className={cx(
            "absolute left-0 right-0 top-[calc(100%+0.25rem)] z-30 origin-top rounded-[var(--rb-r-2xl,14px)] border border-neutral-200 bg-white p-1 shadow-[0_4px_16px_-4px_rgba(0,0,0,0.10)] transition-[opacity,transform] duration-[180ms] ease-[cubic-bezier(0.23,1,0.32,1)] motion-reduce:transition-none dark:border-neutral-800 dark:bg-neutral-900 dark:shadow-none",
            shown ? "scale-100 opacity-100" : "scale-95 opacity-0",
          )}
        >
          {workspaces.map((item) => (
            <button
              key={item.name}
              type="button"
              role="menuitemradio"
              aria-checked={item.name === workspace}
              onClick={() => {
                setWorkspace(item.name);
                close(true);
              }}
              className={cx(
                "flex w-full cursor-pointer items-center gap-2 rounded-[var(--rb-r-lg,10px)] px-2 py-1.5 text-left hover:bg-neutral-100 active:bg-neutral-200 dark:hover:bg-neutral-800 dark:active:bg-neutral-700",
                transition,
                focusInset,
              )}
            >
              <span className="min-w-0 flex-1">
                <span className="block truncate text-[13px] font-medium text-neutral-900 dark:text-neutral-100">
                  {item.name}
                </span>
                <span className="block truncate text-xs text-neutral-500 dark:text-neutral-500">
                  {item.members}
                </span>
              </span>
              {item.name === workspace && (
                <Check
                  aria-hidden="true"
                  className="h-3.5 w-3.5 shrink-0 text-neutral-900 dark:text-neutral-100"
                />
              )}
            </button>
          ))}

        </div>
      )}
    </div>
  );
}

// Chords the command menu advertises (g + key → area) and bare action keys.
// Destinations mirror the menu's command map: an area hash or a route path.
const KEY_DESTINATIONS: Record<string, string> = {
  "g o": "overview",
  "g p": "positions",
  "g a": "assets",
  "g d": "deals",
  "g r": "recon",
  "g e": "reports",
  "g c": "compliance",
  "g l": "alerts",
  "g x": "/admin",
  t: "positions",
  n: "deals",
  r: "recon",
  l: "reports",
  e: "alerts",
};

function NavigationFrame({
  areaId,
  onSelectArea,
  workspaces,
  loadFailed,
  onClose,
}: {
  areaId: string;
  onSelectArea: (id: string) => void;
  workspaces: { name: string; members: string }[];
  loadFailed: boolean;
  onClose?: () => void;
}) {
  const [tipFor, setTipFor] = useState<string | null>(null);
  const [tipShown, setTipShown] = useState(false);
  const nav = useScrollFade<HTMLElement>();
  const timerRef = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const frameRef = useRef<number | undefined>(undefined);
  const openRef = useRef(false);
  const graceRef = useRef(false);
  const graceTimerRef = useRef<ReturnType<typeof setTimeout> | undefined>(
    undefined,
  );
  const reduceMotion = useReducedMotion();

  const area = AREAS.find((item) => item.id === areaId) ?? AREAS[0];

  const showTip = (id: string) => {
    clearTimeout(timerRef.current);
    cancelAnimationFrame(frameRef.current ?? 0);
    const instant = openRef.current || graceRef.current;
    const reveal = () => {
      openRef.current = true;
      setTipFor(id);
      if (instant) {
        setTipShown(true);
      } else {
        setTipShown(false);
        frameRef.current = requestAnimationFrame(() => setTipShown(true));
      }
    };
    if (instant) reveal();
    else timerRef.current = setTimeout(reveal, TOOLTIP_DELAY_MS);
  };

  const hideTip = () => {
    clearTimeout(timerRef.current);
    cancelAnimationFrame(frameRef.current ?? 0);
    if (openRef.current) {
      graceRef.current = true;
      clearTimeout(graceTimerRef.current);
      graceTimerRef.current = setTimeout(() => {
        graceRef.current = false;
      }, TOOLTIP_GRACE_MS);
    }
    openRef.current = false;
    setTipFor(null);
    setTipShown(false);
  };

  useEffect(
    () => () => {
      clearTimeout(timerRef.current);
      clearTimeout(graceTimerRef.current);
      cancelAnimationFrame(frameRef.current ?? 0);
    },
    [],
  );

  const tip = (id: string, label: string) =>
    tipFor === id ? (
      <span
        role="tooltip"
        className={cx(
          "pointer-events-none absolute left-full top-1/2 z-[70] ml-2 flex h-7 origin-left -translate-y-1/2 items-center whitespace-nowrap rounded-[var(--rb-r-sm,6px)] bg-[var(--rb-accent,oklch(20.5%_0_0))] px-2 text-xs text-[var(--rb-accent-fg,oklch(100%_0_0))] transition-[opacity,transform] duration-[125ms] ease-[cubic-bezier(0.23,1,0.32,1)] motion-reduce:transition-none dark:bg-[var(--rb-accent,oklch(100%_0_0))] dark:text-[var(--rb-accent-fg,oklch(20.5%_0_0))]",
          tipShown ? "scale-100 opacity-100" : "scale-95 opacity-0",
        )}
      >
        {label}
      </span>
    ) : null;

  return (
    <>
      <div className="flex w-14 shrink-0 flex-col items-center gap-1 border-r border-neutral-200/70 bg-neutral-50 px-2 py-2 dark:border-neutral-800 dark:bg-neutral-900">
        {onClose && (
          <button
            type="button"
            aria-label="Close navigation"
            onClick={onClose}
            className={cx(
              "mb-1 inline-flex h-9 w-9 shrink-0 cursor-pointer items-center justify-center rounded-[var(--rb-r-md,8px)] bg-neutral-100 text-neutral-700 hover:bg-neutral-200 hover:text-neutral-900 active:bg-neutral-200 dark:bg-neutral-800 dark:text-neutral-300 dark:hover:bg-neutral-700 dark:hover:text-neutral-100 dark:active:bg-neutral-700",
              transition,
              focus,
            )}
          >
            <X aria-hidden="true" className="h-4 w-4 shrink-0" />
          </button>
        )}

        <nav
          aria-label="Areas"
          onMouseLeave={hideTip}
          className="flex flex-col items-center gap-1"
        >
          {AREAS.map((item) => {
            const Icon = item.icon;
            const selected = item.id === areaId;
            return (
              <span key={item.id} className="relative">
                <button
                  type="button"
                  aria-label={item.label}
                  aria-current={selected ? "page" : undefined}
                  onClick={() => {
                    onSelectArea(item.id);
                    hideTip();
                  }}
                  onMouseEnter={() => showTip(item.id)}
                  onFocus={() => showTip(item.id)}
                  onBlur={hideTip}
                  className={cx(
                    "inline-flex h-9 w-9 cursor-pointer items-center justify-center rounded-[var(--rb-r-md,8px)]",
                    selected
                      ? "bg-[var(--rb-accent,oklch(20.5%_0_0))] text-[var(--rb-accent-fg,oklch(100%_0_0))] dark:bg-[var(--rb-accent,oklch(100%_0_0))] dark:text-[var(--rb-accent-fg,oklch(20.5%_0_0))]"
                      : "bg-transparent text-neutral-500 hover:bg-neutral-100 hover:text-neutral-900 active:bg-neutral-200 dark:text-neutral-500 dark:hover:bg-neutral-800 dark:hover:text-neutral-100 dark:active:bg-neutral-700",
                    transition,
                    focus,
                  )}
                >
                  <Icon aria-hidden="true" className="h-4 w-4 shrink-0" />
                </button>
                {tip(item.id, item.label)}
              </span>
            );
          })}
        </nav>
      </div>

      <div className="flex w-64 min-w-0 flex-col bg-neutral-50 dark:bg-neutral-900">
        <div className="shrink-0 px-2 pb-1 pt-2">
          <WorkspaceSwitcher workspaces={workspaces} loadFailed={loadFailed} />
        </div>

        <div className="relative min-h-0 flex-1">
          <nav
            ref={nav.ref}
            onScroll={nav.onScroll}
            aria-label={area.label}
            className="h-full overflow-y-auto px-2 pb-3"
          >
            <motion.div
              key={area.id}
              initial={reduceMotion ? false : { opacity: 0, y: 4 }}
              animate={reduceMotion ? undefined : { opacity: 1, y: 0 }}
              transition={{ duration: 0.18, ease: EASE_OUT }}
              className="space-y-4"
            >
              {area.groups
                .map((group) => ({
                  ...group,
                  // Only the live surface is a real destination — fabricated
                  // saved views/filters and their counts are not rendered.
                  items: group.items.filter((i) => i.label === area.current),
                }))
                .filter((group) => group.items.length > 0)
                .map((group) => (
                  <div key={group.label}>
                    <p className="mb-1 px-3 text-[11px] font-medium uppercase tracking-wider text-neutral-500 dark:text-neutral-500">
                      {group.label}
                    </p>
                    <ul className="space-y-0.5">
                      {group.items.map((item) => (
                        <li key={item.label}>
                          <a
                            href={`#${area.id}`}
                            aria-current="page"
                            className={cx(
                              "flex h-8 cursor-pointer items-center gap-2 rounded-[var(--rb-r-md,8px)] bg-neutral-100 px-3 text-[13px] font-medium text-neutral-900 active:bg-neutral-200 dark:bg-neutral-800 dark:text-neutral-100 dark:active:bg-neutral-700",
                              transition,
                              focus,
                            )}
                          >
                            <span className="min-w-0 flex-1 truncate">
                              {item.label}
                            </span>
                          </a>
                        </li>
                      ))}
                    </ul>
                  </div>
                ))}
            </motion.div>
          </nav>
          <div
            aria-hidden="true"
            className={cx(
              "pointer-events-none absolute inset-x-0 top-0 h-8 bg-gradient-to-b from-neutral-50 to-transparent transition-opacity duration-200 ease-out dark:from-neutral-900",
              nav.edges.start ? "opacity-100" : "opacity-0",
            )}
          />
          <div
            aria-hidden="true"
            className={cx(
              "pointer-events-none absolute inset-x-0 bottom-0 h-8 bg-gradient-to-t from-neutral-50 to-transparent transition-opacity duration-200 ease-out dark:from-neutral-900",
              nav.edges.end ? "opacity-100" : "opacity-0",
            )}
          />
        </div>
      </div>
    </>
  );
}

export default function AppShell2() {
  const [areaId, setAreaId] = useState<string>(() =>
    typeof window !== "undefined" &&
    AREAS.some((a) => a.id === window.location.hash.slice(1))
      ? window.location.hash.slice(1)
      : AREAS[0].id,
  );
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [drawerShown, setDrawerShown] = useState(false);
  const [workspaces, setWorkspaces] = useState<
    { name: string; members: string }[]
  >([]);
  const [workspacesLoadFailed, setWorkspacesLoadFailed] = useState(false);
  const content = useScrollFade<HTMLElement>();
  const shouldFocusRef = useRef(false);
  const rootRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const drawerRef = useRef<HTMLDivElement>(null);
  const reduceMotion = useReducedMotion();

  const area = AREAS.find((item) => item.id === areaId) ?? AREAS[0];

  // location.hash is the source of truth for area selection: sidebar links
  // and ⌘K navigation both write the hash, and this listener keeps state in
  // sync — which also makes areas deep-linkable and Back/Forward work.
  useEffect(() => {
    const onHash = () => {
      const id = window.location.hash.slice(1);
      if (AREAS.some((a) => a.id === id)) setAreaId(id);
    };
    onHash();
    window.addEventListener("hashchange", onHash);
    return () => window.removeEventListener("hashchange", onHash);
  }, []);

  const selectArea = useCallback((id: string) => {
    window.location.hash = id;
    setAreaId(id);
  }, []);

  // Bind the shortcuts the command menu advertises: g+<key> chords switch
  // areas, bare keys run the matching action's navigation. Skipped while
  // typing or while the ⌘K dialog owns the keys.
  useEffect(() => {
    let chord = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const onKey = (event: KeyboardEvent) => {
      if (event.metaKey || event.ctrlKey || event.altKey) return;
      const target = event.target as HTMLElement | null;
      if (
        target?.closest(
          'input, textarea, select, [contenteditable="true"], [role="dialog"]',
        )
      )
        return;
      const key = event.key.toLowerCase();
      if (key === "g") {
        chord = true;
        clearTimeout(timer);
        timer = setTimeout(() => (chord = false), 900);
        return;
      }
      if (key.length !== 1) {
        chord = false;
        return;
      }
      const dest = KEY_DESTINATIONS[chord ? `g ${key}` : key];
      chord = false;
      if (!dest) return;
      event.preventDefault();
      if (dest.startsWith("/")) window.location.assign(dest);
      else selectArea(dest);
    };
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("keydown", onKey);
      clearTimeout(timer);
    };
  }, [selectArea]);

  // First real API read: the caller's tenants drive the vehicle switcher.
  // The proxy (next.config.ts) forwards to the API with the session JWT;
  // on failure the switcher shows an explicit unavailable state — never a
  // fabricated list.
  useEffect(() => {
    let cancelled = false;
    apiFetch("/api/v1/me/access")
      .then((res) => {
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        return res.json();
      })
      .then((body) => {
        if (cancelled) return;
        setWorkspaces(
          ((body?.tenants ?? []) as { slug: string; role: string }[]).map(
            (t) => ({ name: t.slug, members: t.role }),
          ),
        );
      })
      .catch(() => {
        if (!cancelled) setWorkspacesLoadFailed(true);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    const frame = requestAnimationFrame(() => {
      const view = rootRef.current?.ownerDocument.defaultView ?? window;
      if (view.matchMedia("(max-width: 1023px)").matches) setDrawerOpen(true);
    });
    return () => cancelAnimationFrame(frame);
  }, []);

  useEffect(() => {
    if (!drawerOpen) return;
    const drawer = drawerRef.current;
    const doc = drawer?.ownerDocument ?? document;
    const frame = requestAnimationFrame(() => setDrawerShown(true));

    const getFocusable = () =>
      Array.from(
        drawer?.querySelectorAll<HTMLElement>(
          'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])',
        ) ?? [],
      ).filter((element) => !element.hasAttribute("disabled"));

    if (shouldFocusRef.current) {
      getFocusable()[0]?.focus({ preventScroll: true });
      shouldFocusRef.current = false;
    }

    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        setDrawerOpen(false);
        setDrawerShown(false);
        triggerRef.current?.focus({ preventScroll: true });
        return;
      }
      if (event.key !== "Tab") return;
      const focusable = getFocusable();
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (!first || !last) return;
      if (event.shiftKey && doc.activeElement === first) {
        event.preventDefault();
        last.focus({ preventScroll: true });
      } else if (!event.shiftKey && doc.activeElement === last) {
        event.preventDefault();
        first.focus({ preventScroll: true });
      }
    };

    doc.addEventListener("keydown", onKeyDown);
    return () => {
      cancelAnimationFrame(frame);
      doc.removeEventListener("keydown", onKeyDown);
    };
  }, [drawerOpen]);

  const closeDrawer = () => {
    setDrawerOpen(false);
    setDrawerShown(false);
    triggerRef.current?.focus({ preventScroll: true });
  };

  return (
    <div
      ref={rootRef}
      className="relative flex h-full min-h-[720px] w-full overflow-hidden bg-white dark:bg-neutral-950"
    >
      <aside className="hidden shrink-0 lg:flex">
        <NavigationFrame
          areaId={areaId}
          onSelectArea={selectArea}
          workspaces={workspaces}
          loadFailed={workspacesLoadFailed}
        />
      </aside>

      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex h-14 shrink-0 items-center gap-3 border-b border-neutral-200/70 px-4 sm:px-6 dark:border-neutral-800">
          <button
            ref={triggerRef}
            type="button"
            aria-label="Open navigation"
            aria-expanded={drawerOpen}
            onClick={() => {
              shouldFocusRef.current = true;
              setDrawerOpen(true);
            }}
            className={cx(
              "inline-flex h-8 w-8 shrink-0 cursor-pointer items-center justify-center rounded-[var(--rb-r-md,8px)] bg-neutral-100 text-neutral-700 hover:bg-neutral-200 active:bg-neutral-200 lg:hidden dark:bg-neutral-800 dark:text-neutral-300 dark:hover:bg-neutral-700 dark:active:bg-neutral-700",
              transition,
              focus,
            )}
          >
            <Menu aria-hidden="true" className="h-4 w-4 shrink-0" />
          </button>

          <h1 className="min-w-0 flex-1 truncate text-xl font-medium tracking-[-0.015em] text-neutral-900 dark:text-neutral-100">
            {area.title}
          </h1>

          <button
            type="button"
            aria-label="Commands (⌘K)"
            onClick={() => {
              // The command menu's public API is its document-level ⌘K
              // listener — a synthetic event opens it without prop drilling.
              document.dispatchEvent(
                new KeyboardEvent("keydown", {
                  key: "k",
                  metaKey: true,
                  bubbles: true,
                }),
              );
            }}
            className={cx(
              "inline-flex h-8 shrink-0 cursor-pointer items-center gap-1.5 justify-center rounded-[var(--rb-r-md,8px)] bg-[var(--rb-accent,oklch(20.5%_0_0))] px-2.5 text-[13px] font-medium text-[var(--rb-accent-fg,oklch(100%_0_0))] transition-[background-color,transform] duration-150 ease-[cubic-bezier(0.23,1,0.32,1)] motion-reduce:transition-none hover:bg-[color-mix(in_oklab,var(--rb-accent,oklch(20.5%_0_0))_90%,transparent)] active:scale-[0.97] motion-reduce:active:scale-100 dark:bg-[var(--rb-accent,oklch(100%_0_0))] dark:text-[var(--rb-accent-fg,oklch(20.5%_0_0))] dark:hover:bg-[color-mix(in_oklab,var(--rb-accent,oklch(100%_0_0))_90%,transparent)]",
              focus,
            )}
          >
            Commands
            <kbd className="font-mono text-[11px] opacity-70">⌘K</kbd>
          </button>

          <SessionMenu />
        </header>

        <div className="relative min-h-0 flex-1">
          <main
            ref={content.ref}
            onScroll={content.onScroll}
            className="h-full overflow-y-auto p-4 sm:p-6"
          >
            {area.id === "overview" ? (
              <Dashboard4 />
            ) : area.id === "assets" ? (
              <DataTable3 />
            ) : area.id === "deals" ? (
              <PipelineBoard />
            ) : area.id === "reports" ? (
              <ReportQueue />
            ) : area.id === "recon" ? (
              <ReconPanel />
            ) : area.id === "compliance" ? (
              <CompliancePanel />
            ) : area.id === "alerts" ? (
              <AgentRunsPanel />
            ) : (
            <motion.div
              key={area.id}
              initial={reduceMotion ? false : { opacity: 0, y: 4 }}
              animate={reduceMotion ? undefined : { opacity: 1, y: 0 }}
              transition={{ duration: 0.18, ease: EASE_OUT }}
              className="overflow-hidden rounded-[var(--rb-r-2xl,14px)] border border-neutral-200/70 bg-white dark:border-neutral-800 dark:bg-neutral-900"
            >
              <div className="flex h-12 items-center gap-3 bg-neutral-50 px-4 dark:bg-neutral-800/40">
                <h2 className="min-w-0 flex-1 truncate text-[13px] font-medium text-neutral-900 dark:text-neutral-100">
                  {area.listTitle}
                </h2>
                <span className="shrink-0 text-xs tabular-nums text-neutral-500 dark:text-neutral-500">
                  {area.listCount}
                </span>
              </div>

              <ul className="divide-y divide-neutral-100 dark:divide-neutral-800/70">
                {area.rows.map((row) => (
                  <li key={row.name}>
                    <button
                      type="button"
                      className={cx(
                        "flex w-full cursor-pointer items-center gap-3 px-4 py-2.5 text-left hover:bg-neutral-50 active:bg-neutral-100 dark:hover:bg-neutral-800/50 dark:active:bg-neutral-800",
                        transition,
                        focusInset,
                      )}
                    >
                      <span className="min-w-0 flex-1">
                        <span className="block truncate text-[13px] font-medium text-neutral-900 dark:text-neutral-100">
                          {row.name}
                        </span>
                        <span className="mt-0.5 block truncate text-xs text-neutral-500 dark:text-neutral-500">
                          {row.detail}
                        </span>
                      </span>
                      <span className="flex shrink-0 flex-col items-end">
                        <span
                          className={cx(
                            "block text-[13px] tabular-nums",
                            row.meta === "-"
                              ? "text-neutral-400 dark:text-neutral-600"
                              : "text-neutral-900 dark:text-neutral-100",
                          )}
                        >
                          {row.meta}
                        </span>
                        <span className="mt-0.5 flex items-center gap-1.5 text-xs text-neutral-500 dark:text-neutral-500">
                          <span
                            aria-hidden="true"
                            className={cx(
                              "h-1.5 w-1.5 shrink-0 rounded-full",
                              DOT[row.tone],
                            )}
                          />
                          {row.status}
                        </span>
                      </span>
                    </button>
                  </li>
                ))}
              </ul>
            </motion.div>
            )}
          </main>
          <div
            aria-hidden="true"
            className={cx(
              "pointer-events-none absolute inset-x-0 top-0 h-8 bg-gradient-to-b from-white to-transparent transition-opacity duration-200 ease-out dark:from-neutral-950",
              content.edges.start ? "opacity-100" : "opacity-0",
            )}
          />
          <div
            aria-hidden="true"
            className={cx(
              "pointer-events-none absolute inset-x-0 bottom-0 h-8 bg-gradient-to-t from-white to-transparent transition-opacity duration-200 ease-out dark:from-neutral-950",
              content.edges.end ? "opacity-100" : "opacity-0",
            )}
          />
        </div>
      </div>

      {drawerOpen && (
        <div className="lg:hidden">
          <button
            type="button"
            aria-label="Close navigation overlay"
            tabIndex={-1}
            onClick={closeDrawer}
            className={cx(
              "absolute inset-0 z-30 cursor-pointer bg-neutral-950/40 backdrop-blur-[2px] transition-opacity duration-200 ease-out motion-reduce:transition-none dark:bg-neutral-950/60",
              drawerShown ? "opacity-100" : "opacity-0",
            )}
          />
          <div
            ref={drawerRef}
            role="dialog"
            aria-modal="true"
            aria-label="Navigation"
            className={cx(
              "absolute inset-y-0 left-0 z-40 flex w-[312px] max-w-[calc(100%-3rem)] overflow-hidden rounded-r-[var(--rb-r-4xl,18px)] bg-neutral-50 shadow-[0_16px_48px_-12px_rgba(0,0,0,0.18)] transition-transform duration-200 ease-[cubic-bezier(0.32,0.72,0,1)] motion-reduce:transition-none dark:bg-neutral-900 dark:shadow-[0_16px_48px_-12px_rgba(0,0,0,0.6)]",
              drawerShown ? "translate-x-0" : "-translate-x-full",
            )}
          >
            <NavigationFrame
              areaId={areaId}
              onSelectArea={selectArea}
              workspaces={workspaces}
              loadFailed={workspacesLoadFailed}
              onClose={closeDrawer}
            />
          </div>
        </div>
      )}
    </div>
  );
}
