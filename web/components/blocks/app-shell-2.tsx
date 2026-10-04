"use client";

import { Fragment, useCallback, useEffect, useRef, useState } from "react";
import {
  Bell,
  Brain,
  ChartPie,
  Check,
  ChevronsUpDown,
  FileBarChart,
  GitCompareArrows,
  Kanban,
  Landmark,
  LayoutDashboard,
  Menu,
  Scale,
  TrendingUp,
  X,
  type LucideIcon,
} from "lucide-react";
import { useScrollFade } from "@/lib/use-scroll-fade";
import Dashboard4 from "@/components/blocks/dashboard-4";
import PipelineBoard from "@/components/pipeline-board";
import ReportQueue from "@/components/report-queue";
import ReconPanel from "@/components/recon-panel";
import CompliancePanel from "@/components/compliance-panel";
import AgentRunsPanel from "@/components/agent-runs-panel";
import AnalysisPanel from "@/components/analysis-panel";
import BrainPanel from "@/components/brain-panel";
import SessionMenu from "@/components/session-menu";
import { usePlatformAdmin, useTenants } from "@/lib/use-tenants";

const cx = (...c: (string | false | null | undefined)[]) =>
  c.filter(Boolean).join(" ");

const focus =
  "focus-visible:outline-none focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--rb-accent,oklch(20.5%_0_0))] dark:focus-visible:outline-[var(--rb-accent,oklch(100%_0_0))]";

const focusInset =
  "focus-visible:outline-none focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-[var(--rb-accent,oklch(20.5%_0_0))] dark:focus-visible:outline-[var(--rb-accent,oklch(100%_0_0))]";

const transition =
  "transition-[background-color,border-color,color] duration-150 ease-out";

type Area = {
  id: string;
  label: string;
  icon: LucideIcon;
  title: string;
  // No read endpoint serves the area yet: listed apart, never with stand-in data.
  pending?: boolean;
};

const AREAS: Area[] = [
  { id: "overview", label: "Overview", icon: LayoutDashboard, title: "Portfolio overview" },
  { id: "deals", label: "Deals", icon: Kanban, title: "Deal pipeline" },
  { id: "recon", label: "Reconciliation", icon: GitCompareArrows, title: "Reconciliation" },
  { id: "reports", label: "Reports", icon: FileBarChart, title: "Reports" },
  { id: "compliance", label: "Compliance", icon: Scale, title: "Compliance" },
  { id: "alerts", label: "Alerts & agents", icon: Bell, title: "Alerts & agents" },
  { id: "brain", label: "Company brain", icon: Brain, title: "Company brain" },
  { id: "analysis", label: "Analysis", icon: TrendingUp, title: "Analysis" },
  { id: "assets", label: "Assets", icon: Landmark, title: "Assets", pending: true },
  { id: "positions", label: "Positions", icon: ChartPie, title: "Positions", pending: true },
];

function WorkspaceSwitcher() {
  const { tenants, tenantId, setTenantId, loading, error } = useTenants();
  const [open, setOpen] = useState(false);
  const [shown, setShown] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);

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

  const active = tenants.find((t) => t.tenantId === tenantId);

  // An empty list is honest state, not a menu — never offer fabricated names.
  if (!active) {
    return (
      <div
        className={cx(
          "flex h-11 w-full items-center gap-2 rounded-[var(--rb-r-lg,10px)] bg-neutral-100 px-2 text-left dark:bg-neutral-800",
          transition,
        )}
      >
        <span className="min-w-0 flex-1">
          <span className="block truncate text-[13px] font-medium text-neutral-900 dark:text-neutral-100">
            {loading ? "Loading workspaces…" : error ? "Workspaces unavailable" : "No workspaces"}
          </span>
          <span className="block truncate text-xs text-neutral-500 dark:text-neutral-500">
            {loading ? "\u00a0" : (error ?? "none assigned to you")}
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
        aria-label={`Workspace: ${active.slug} (${active.role}). Switch workspace`}
        onClick={() => (open ? close(false) : setOpen(true))}
        className={cx(
          "flex h-11 w-full cursor-pointer items-center gap-2 rounded-[var(--rb-r-lg,10px)] bg-neutral-100 px-2 text-left hover:bg-neutral-200 active:bg-neutral-200 dark:bg-neutral-800 dark:hover:bg-neutral-700 dark:active:bg-neutral-700",
          transition,
          focus,
        )}
      >
        <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-[var(--rb-r-md,8px)] bg-neutral-300 text-xs font-medium text-neutral-700 dark:bg-neutral-600 dark:text-neutral-100">
          {active.slug.charAt(0).toUpperCase()}
        </span>
        <span className="min-w-0 flex-1">
          <span className="block truncate text-[13px] font-medium text-neutral-900 dark:text-neutral-100">
            {active.slug}
          </span>
          <span className="block truncate text-xs text-neutral-500 dark:text-neutral-500">
            {active.role}
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
          aria-label="Switch workspace"
          className={cx(
            "absolute left-0 right-0 top-[calc(100%+0.25rem)] z-30 origin-top rounded-[var(--rb-r-2xl,14px)] border border-neutral-200 bg-white p-1 shadow-[0_4px_16px_-4px_rgba(0,0,0,0.10)] transition-[opacity,transform] duration-[180ms] ease-[cubic-bezier(0.23,1,0.32,1)] motion-reduce:transition-none dark:border-neutral-800 dark:bg-neutral-900 dark:shadow-none",
            shown ? "scale-100 opacity-100" : "scale-95 opacity-0",
          )}
        >
          {tenants.map((item) => (
            <button
              key={item.tenantId}
              type="button"
              role="menuitemradio"
              aria-checked={item.tenantId === tenantId}
              onClick={() => {
                setTenantId(item.tenantId);
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
                  {item.slug}
                </span>
                <span className="block truncate text-xs text-neutral-500 dark:text-neutral-500">
                  {item.role}
                </span>
              </span>
              {item.tenantId === tenantId && (
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

/**
 * Honest full-area state: an area the API doesn't serve yet, or a workspace
 * gate — no fabricated rows, counts, or saved-view stand-ins (#314).
 */
function StatePanel({
  title,
  detail,
  onRetry,
}: {
  title: string;
  detail: string;
  onRetry?: () => void;
}) {
  return (
    <div className="flex h-full items-center justify-center p-6">
      <div className="max-w-md rounded-[var(--rb-r-2xl,14px)] border border-neutral-200/70 bg-white px-6 py-10 text-center dark:border-neutral-800 dark:bg-neutral-900">
        <h2 className="text-sm font-medium text-neutral-900 dark:text-neutral-100">
          {title}
        </h2>
        <p className="mt-2 text-[13px] leading-relaxed text-neutral-500 dark:text-neutral-400">
          {detail}
        </p>
        {onRetry && (
          <button
            type="button"
            onClick={onRetry}
            className={cx(
              "mt-4 inline-flex h-8 cursor-pointer items-center rounded-[var(--rb-r-md,8px)] bg-neutral-100 px-3 text-[13px] font-medium text-neutral-700 hover:bg-neutral-200 dark:bg-neutral-800 dark:text-neutral-300 dark:hover:bg-neutral-700",
              transition,
              focus,
            )}
          >
            Try again
          </button>
        )}
      </div>
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
  "g b": "brain",
  "g m": "analysis",
  n: "deals",
  r: "recon",
  l: "reports",
};

function NavigationFrame({
  areaId,
  onSelectArea,
  onClose,
}: {
  areaId: string;
  onSelectArea: (id: string) => void;
  onClose?: () => void;
}) {
  const nav = useScrollFade<HTMLElement>();

  const link = (item: Area) => {
    const Icon = item.icon;
    const selected = item.id === areaId;
    return (
      <li key={item.id}>
        <a
          href={`#${item.id}`}
          aria-current={selected ? "page" : undefined}
          onClick={(event) => {
            event.preventDefault();
            onSelectArea(item.id);
          }}
          className={cx(
            "flex h-8 items-center gap-2.5 rounded-[var(--rb-r-md,8px)] px-2.5 text-[13px]",
            selected
              ? "bg-neutral-200/70 font-medium text-neutral-900 dark:bg-neutral-800 dark:text-neutral-100"
              : item.pending
                ? "text-neutral-400 hover:bg-neutral-100 hover:text-neutral-600 dark:text-neutral-500 dark:hover:bg-neutral-800/60 dark:hover:text-neutral-300"
                : "text-neutral-600 hover:bg-neutral-100 hover:text-neutral-900 dark:text-neutral-400 dark:hover:bg-neutral-800/60 dark:hover:text-neutral-100",
            transition,
            focusInset,
          )}
        >
          <Icon aria-hidden="true" className="h-4 w-4 shrink-0" />
          <span className="min-w-0 flex-1 truncate">{item.label}</span>
        </a>
      </li>
    );
  };

  return (
    <div className="flex w-60 min-w-0 flex-col border-r border-neutral-200/70 bg-neutral-50 dark:border-neutral-800 dark:bg-neutral-900">
      <div className="flex shrink-0 items-center gap-1 px-2 pb-1 pt-2">
        <div className="min-w-0 flex-1">
          <WorkspaceSwitcher />
        </div>
        {onClose && (
          <button
            type="button"
            aria-label="Close navigation"
            onClick={onClose}
            className={cx(
              "inline-flex h-9 w-9 shrink-0 cursor-pointer items-center justify-center rounded-[var(--rb-r-md,8px)] text-neutral-500 hover:bg-neutral-200 hover:text-neutral-900 dark:hover:bg-neutral-800 dark:hover:text-neutral-100",
              transition,
              focus,
            )}
          >
            <X aria-hidden="true" className="h-4 w-4 shrink-0" />
          </button>
        )}
      </div>

      <div className="relative min-h-0 flex-1">
        <nav
          ref={nav.ref}
          onScroll={nav.onScroll}
          aria-label="Areas"
          className="h-full overflow-y-auto px-2 py-2"
        >
          <ul className="space-y-0.5">{AREAS.filter((a) => !a.pending).map(link)}</ul>
          <p className="mb-1 mt-5 px-2.5 text-[11px] font-medium uppercase tracking-wider text-neutral-500">
            Not yet available
          </p>
          <ul className="space-y-0.5">{AREAS.filter((a) => a.pending).map(link)}</ul>
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
  const {
    tenants,
    tenantId,
    loading: tenantsLoading,
    error: tenantError,
    retry: retryTenants,
  } = useTenants();
  // Gates the g+x chord to the ops console (#312); null (loading) counts as no.
  // Read through a ref so the verdict landing doesn't re-subscribe the key
  // handler mid-chord (a pending "g" would be dropped and "g l" open Reports).
  const isPlatformAdmin = usePlatformAdmin() === true;
  const platformAdmin = useRef(isPlatformAdmin);
  useEffect(() => {
    platformAdmin.current = isPlatformAdmin;
  }, [isPlatformAdmin]);
  const content = useScrollFade<HTMLElement>();
  const shouldFocusRef = useRef(false);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const drawerRef = useRef<HTMLDivElement>(null);
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
      if (!dest || (dest === "/admin" && !platformAdmin.current)) return;
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
      // Esc inside the workspace menu closes only that menu (its own handler).
      if ((event.target as Element | null)?.closest?.('[role="menu"]')) return;
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
      className="relative flex h-full min-h-[720px] w-full overflow-hidden bg-white dark:bg-neutral-950"
    >
      <aside className="hidden shrink-0 lg:flex">
        <NavigationFrame
          areaId={areaId}
          onSelectArea={selectArea}
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
            {/* Keyed by workspace: switching tenants remounts the area, so one
                tenant's inputs or results never render under another. */}
            <Fragment key={tenantId}>
              {tenantsLoading ? (
                <div className="flex h-full items-center justify-center">
                  <div
                    role="status"
                    aria-label="Loading workspaces"
                    className="h-6 w-6 animate-spin rounded-full border-2 border-neutral-300 border-t-neutral-900 motion-reduce:animate-none dark:border-neutral-700 dark:border-t-neutral-100"
                  />
                </div>
              ) : tenantError ? (
                <StatePanel
                  title="Workspaces unavailable"
                  detail={`Your workspace memberships could not be loaded — ${tenantError}.`}
                  onRetry={retryTenants}
                />
              ) : tenants.length === 0 ? (
                <StatePanel
                  title="No workspace access yet"
                  detail="Your account is not a member of any workspace. Ask a workspace admin to add you, then reload this page."
                />
              ) : area.id === "overview" ? (
                <Dashboard4 />
              ) : area.id === "assets" ? (
                <StatePanel
                  title="Asset register"
                  detail="Assets are recorded one by one, but no register view lists them yet, so there are no rows to show."
                />
              ) : area.id === "positions" ? (
                <StatePanel
                  title="Positions"
                  detail="Positions derive from the transaction ledger, but no positions view is served yet. The overview shows live pipeline and agent activity in the meantime."
                />
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
              ) : area.id === "brain" ? (
                <BrainPanel />
              ) : area.id === "analysis" ? (
                <AnalysisPanel />
              ) : null}
            </Fragment>
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
              "absolute inset-y-0 left-0 z-40 flex w-60 max-w-[calc(100%-3rem)] overflow-hidden rounded-r-[var(--rb-r-4xl,18px)] bg-neutral-50 shadow-[0_16px_48px_-12px_rgba(0,0,0,0.18)] transition-transform duration-200 ease-[cubic-bezier(0.32,0.72,0,1)] motion-reduce:transition-none dark:bg-neutral-900 dark:shadow-[0_16px_48px_-12px_rgba(0,0,0,0.6)]",
              drawerShown ? "translate-x-0" : "-translate-x-full",
            )}
          >
            <NavigationFrame
              areaId={areaId}
              onSelectArea={(id) => {
                selectArea(id);
                closeDrawer();
              }}
              onClose={closeDrawer}
            />
          </div>
        </div>
      )}
    </div>
  );
}
