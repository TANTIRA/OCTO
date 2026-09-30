"use client";

import {
  useCallback,
  useEffect,
  useId,
  useMemo,
  useRef,
  useState,
  type KeyboardEvent,
  type ReactNode,
} from "react";
import {
  Activity,
  Bell,
  Brain,
  ChartPie,
  CornerDownLeft,
  FileBarChart,
  FileText,
  GitCompareArrows,
  Kanban,
  Landmark,
  LayoutDashboard,
  Scale,
  Search,
  UserPlus,
  type LucideIcon,
} from "lucide-react";
import { useScrollFade } from "@/lib/use-scroll-fade";
import { usePlatformAdmin } from "@/lib/use-tenants";

const cx = (...c: (string | false | null | undefined)[]) =>
  c.filter(Boolean).join(" ");

type Command = {
  id: string;
  group: string;
  title: string;
  detail?: string;
  shortcut: string;
  icon: LucideIcon;
};

const COMMANDS: Command[] = [
  {
    id: "go-overview",
    group: "Navigation",
    title: "Portfolio overview",
    shortcut: "G O",
    icon: LayoutDashboard,
  },
  {
    id: "go-positions",
    group: "Navigation",
    title: "Positions",
    shortcut: "G P",
    icon: ChartPie,
  },
  {
    id: "go-assets",
    group: "Navigation",
    title: "Asset register",
    shortcut: "G A",
    icon: Landmark,
  },
  {
    id: "go-deals",
    group: "Navigation",
    title: "Deal pipeline",
    shortcut: "G D",
    icon: Kanban,
  },
  {
    id: "go-recon",
    group: "Navigation",
    title: "Reconciliation",
    shortcut: "G R",
    icon: GitCompareArrows,
  },
  {
    id: "go-reports",
    group: "Navigation",
    title: "Reports",
    shortcut: "G E",
    icon: FileBarChart,
  },
  {
    id: "go-compliance",
    group: "Navigation",
    title: "Compliance",
    shortcut: "G C",
    icon: Scale,
  },
  {
    id: "go-alerts",
    group: "Navigation",
    title: "Alerts & agents",
    shortcut: "G L",
    icon: Bell,
  },
  {
    id: "go-ops",
    group: "Navigation",
    title: "Ops console",
    detail: "Live API health and surface links",
    shortcut: "G X",
    icon: Activity,
  },
  {
    id: "go-brain",
    group: "Navigation",
    title: "Company brain",
    detail: "Ask a natural-language question",
    shortcut: "G B",
    icon: Brain,
  },
  {
    id: "do-position",
    group: "Actions",
    title: "Record a transaction",
    detail: "Post a ledger event to the IBOR",
    shortcut: "T",
    icon: FileText,
  },
  {
    id: "do-prospect",
    group: "Actions",
    title: "Add a prospect",
    detail: "Create a deal in Sourced",
    shortcut: "N",
    icon: UserPlus,
  },
  {
    id: "do-recon",
    group: "Actions",
    title: "Run reconciliation",
    detail: "Custodian vs IBOR, latest feeds",
    shortcut: "R",
    icon: GitCompareArrows,
  },
  {
    id: "do-report",
    group: "Actions",
    title: "Generate LP report",
    detail: "Quarterly pack for a fund",
    shortcut: "L",
    icon: FileText,
  },
  {
    id: "do-alert-rule",
    group: "Actions",
    title: "New alert rule",
    detail: "Threshold or agent-triggered alert",
    shortcut: "E",
    icon: Bell,
  },
];

const GROUP_ORDER = ["Navigation", "Actions"];

// Commands navigate by setting the area hash — the app shell owns area
// selection and keeps location.hash in sync (deep links + back button).
const AREA_BY_COMMAND: Record<string, string> = {
  "go-overview": "overview",
  "go-positions": "positions",
  "go-assets": "assets",
  "go-deals": "deals",
  "go-recon": "recon",
  "go-reports": "reports",
  "go-compliance": "compliance",
  "go-alerts": "alerts",
  "do-position": "positions",
  "do-prospect": "deals",
  "do-recon": "recon",
  "do-report": "reports",
  "do-alert-rule": "alerts",
};

// Commands that leave the shell entirely (route-level navigation).
const PATH_BY_COMMAND: Record<string, string> = {
  "go-ops": "/admin",
  "go-brain": "/brain",
};

const EXIT_MS = 140;

const FOCUSABLE =
  'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

function matches(command: Command, query: string) {
  const q = query.trim().toLowerCase();
  if (!q) return true;
  return `${command.title} ${command.detail ?? ""} ${command.group}`
    .toLowerCase()
    .includes(q);
}

function Kbd({ children }: { children: ReactNode }) {
  return (
    <kbd className="inline-flex h-5 min-w-5 items-center justify-center rounded-[var(--rb-r-sm,6px)] bg-neutral-100 px-1.5 font-mono text-[11px] text-neutral-600 dark:bg-neutral-800 dark:text-neutral-400">
      {children}
    </kbd>
  );
}

export default function CommandMenu1() {
  const uid = useId();
  const rootRef = useRef<HTMLElement>(null);
  const dialogRef = useRef<HTMLDivElement>(null);
  const list = useScrollFade<HTMLDivElement>();
  const inputRef = useRef<HTMLInputElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const frameRef = useRef<number | undefined>(undefined);
  const timerRef = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

  const [open, setOpen] = useState(false);
  const [shown, setShown] = useState(false);
  const [query, setQuery] = useState("");
  const [activeIndex, setActiveIndex] = useState(0);

  const listboxId = `${uid}-listbox`;
  // The ops console is platform-admin only (#312) — hidden until the API says so.
  const platformAdmin = usePlatformAdmin();
  const filtered = useMemo(
    () =>
      COMMANDS.filter(
        (command) =>
          (command.id !== "go-ops" || platformAdmin === true) &&
          matches(command, query),
      ),
    [query, platformAdmin],
  );
  const activeCommand = filtered[activeIndex];
  const activeId = activeCommand ? `${uid}-${activeCommand.id}` : undefined;

  useEffect(() => {
    if (open) inputRef.current?.focus({ preventScroll: true });
  }, [open]);

  useEffect(() => {
    const scroller = list.ref.current;
    const row = scroller?.querySelector<HTMLElement>('[data-active="true"]');
    if (!scroller || !row) return;
    const listBox = scroller.getBoundingClientRect();
    const rowBox = row.getBoundingClientRect();
    if (rowBox.top < listBox.top) {
      scroller.scrollTop -= listBox.top - rowBox.top;
    } else if (rowBox.bottom > listBox.bottom) {
      scroller.scrollTop += rowBox.bottom - listBox.bottom;
    }
  }, [activeIndex, query, list.ref]);

  useEffect(
    () => () => {
      cancelAnimationFrame(frameRef.current ?? 0);
      clearTimeout(timerRef.current);
    },
    [],
  );

  const openMenu = useCallback((animate: boolean) => {
    clearTimeout(timerRef.current);
    setOpen(true);
    if (animate) {
      setShown(false);
      frameRef.current = requestAnimationFrame(() => setShown(true));
    } else {
      setShown(true);
    }
  }, []);

  const close = useCallback(() => {
    setShown(false);
    clearTimeout(timerRef.current);
    timerRef.current = setTimeout(() => {
      setOpen(false);
      triggerRef.current?.focus({ preventScroll: true });
    }, EXIT_MS);
  }, []);

  useEffect(() => {
    const doc = rootRef.current?.ownerDocument ?? document;
    const onKeyDown = (event: globalThis.KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "k") {
        event.preventDefault();
        openMenu(false);
      }
    };
    doc.addEventListener("keydown", onKeyDown);
    return () => doc.removeEventListener("keydown", onKeyDown);
  }, [openMenu]);

  const run = useCallback(
    (command?: Command) => {
      const target = command ?? activeCommand;
      const area = target ? AREA_BY_COMMAND[target.id] : undefined;
      const path = target ? PATH_BY_COMMAND[target.id] : undefined;
      if (path) window.location.assign(path);
      else if (area) window.location.hash = area;
      setQuery("");
      setActiveIndex(0);
      close();
    },
    [activeCommand, close],
  );
  const move = (delta: number) => {
    setActiveIndex((i) => {
      const count = filtered.length;
      if (count === 0) return 0;
      return (i + delta + count) % count;
    });
  };

  const onInputKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    switch (event.key) {
      case "ArrowDown":
        event.preventDefault();
        move(1);
        break;
      case "ArrowUp":
        event.preventDefault();
        move(-1);
        break;
      case "Home":
        event.preventDefault();
        setActiveIndex(0);
        break;
      case "End":
        event.preventDefault();
        setActiveIndex(Math.max(filtered.length - 1, 0));
        break;
      case "Enter":
        if (!activeCommand) break;
        event.preventDefault();
        run(activeCommand);
        break;
    }
  };

  const onDialogKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key === "Escape") {
      event.preventDefault();
      close();
      return;
    }
    if (event.key !== "Tab") return;
    const root = dialogRef.current;
    if (!root) return;
    const nodes = Array.from(root.querySelectorAll<HTMLElement>(FOCUSABLE));
    if (nodes.length === 0) return;
    const first = nodes[0];
    const last = nodes[nodes.length - 1];
    const focused = root.ownerDocument.activeElement;
    if (event.shiftKey && focused === first) {
      event.preventDefault();
      last.focus({ preventScroll: true });
    } else if (!event.shiftKey && focused === last) {
      event.preventDefault();
      first.focus({ preventScroll: true });
    }
  };

  return (
    <section
      ref={rootRef}
      className="pointer-events-none fixed inset-0 z-[80] flex items-center justify-center p-4"
    >
      <button
        ref={triggerRef}
        type="button"
        aria-haspopup="dialog"
        aria-expanded={open}
        onClick={() => openMenu(true)}
        className="hidden rounded-[var(--rb-r-md,8px)] border border-neutral-200 bg-white px-3 text-sm font-medium text-neutral-900 transition-[transform,background-color,border-color,color] duration-150 ease-[cubic-bezier(0.23,1,0.32,1)] hover:bg-neutral-50 active:scale-[0.97] focus-visible:outline-none focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--rb-accent,oklch(20.5%_0_0))] disabled:pointer-events-none disabled:opacity-50 dark:border-neutral-800 dark:bg-neutral-900 dark:text-neutral-100 dark:hover:bg-neutral-800 dark:focus-visible:outline-[var(--rb-accent,oklch(100%_0_0))]"
      >
        <Search aria-hidden="true" className="h-4 w-4 shrink-0" />
        <span className="min-w-0 flex-1 truncate text-left">
          Search or run a command
        </span>
        <Kbd>⌘K</Kbd>
      </button>

      {open && (
        <>
          <button
            type="button"
            tabIndex={-1}
            aria-label="Close menu"
            onClick={close}
            className={cx(
              "pointer-events-auto absolute inset-0 z-40 cursor-default bg-neutral-950/40 backdrop-blur-[2px] transition-opacity ease-[cubic-bezier(0.23,1,0.32,1)] dark:bg-neutral-950/60",
              shown ? "opacity-100 duration-200" : "opacity-0 duration-[140ms]",
            )}
          />
          <div
            ref={dialogRef}
            role="dialog"
            aria-modal="true"
            aria-labelledby={`${uid}-title`}
            onKeyDown={onDialogKeyDown}
            className={cx(
              "pointer-events-auto absolute left-1/2 top-[12%] z-50 flex max-h-[76%] w-[calc(100%-2rem)] max-w-xl -translate-x-1/2 flex-col overflow-hidden rounded-[var(--rb-r-4xl,18px)] border border-neutral-200/70 bg-white shadow-[0_16px_48px_-12px_rgba(0,0,0,0.18)] transition-[opacity,transform] ease-[cubic-bezier(0.23,1,0.32,1)] motion-reduce:scale-100 motion-reduce:transition-opacity dark:border-neutral-800 dark:bg-neutral-900 dark:shadow-none",
              shown
                ? "scale-100 opacity-100 duration-200"
                : "scale-[0.97] opacity-0 duration-[140ms]",
            )}
          >
            <h2 id={`${uid}-title`} className="sr-only">
              Search or run a command
            </h2>

            <div className="flex h-12 shrink-0 items-center gap-3 px-4">
              <Search
                aria-hidden="true"
                className="h-4 w-4 shrink-0 text-neutral-500 dark:text-neutral-500"
              />
              <label htmlFor={`${uid}-input`} className="sr-only">
                Search or run a command
              </label>
              <input
                ref={inputRef}
                id={`${uid}-input`}
                role="combobox"
                aria-expanded="true"
                aria-controls={listboxId}
                aria-activedescendant={activeId}
                aria-autocomplete="list"
                value={query}
                onChange={(event) => {
                  setQuery(event.target.value);
                  setActiveIndex(0);
                }}
                onKeyDown={onInputKeyDown}
                placeholder="Search or run a command"
                className="h-9 min-w-0 flex-1 bg-transparent text-sm text-neutral-900 placeholder:text-neutral-500 focus:outline-none dark:text-neutral-100 dark:placeholder:text-neutral-500"
              />
            </div>

            <div className="relative flex min-h-0 flex-1 flex-col">
              <div
                ref={list.ref}
                onScroll={list.onScroll}
                className="min-h-0 flex-1 overflow-y-auto bg-neutral-50 p-1.5 dark:bg-neutral-950/50"
              >
                {filtered.length > 0 ? (
                  <div id={listboxId} role="listbox" aria-label="Commands">
                    {GROUP_ORDER.map((group) => {
                      const rows = filtered.filter((c) => c.group === group);
                      if (rows.length === 0) return null;
                      const groupId = `${uid}-${group}`;
                      return (
                        <div
                          key={group}
                          role="group"
                          aria-labelledby={groupId}
                          className="mb-1 last:mb-0"
                        >
                          <p
                            id={groupId}
                            className="px-2.5 pb-1 pt-2 text-[11px] font-medium uppercase tracking-wider text-neutral-500 dark:text-neutral-500"
                          >
                            {group}
                          </p>
                          {rows.map((command) => {
                            const index = filtered.indexOf(command);
                            const Icon = command.icon;
                            const selected = index === activeIndex;
                            return (
                              <button
                                key={command.id}
                                id={`${uid}-${command.id}`}
                                role="option"
                                aria-selected={selected}
                                data-active={selected}
                                type="button"
                                tabIndex={-1}
                                onPointerMove={() => setActiveIndex(index)}
                                onMouseDown={(event) => {
                                  event.preventDefault();
                                  setActiveIndex(index);
                                  run(command);
                                }}
                                className={cx(
                                  "flex min-h-11 w-full cursor-pointer items-center gap-3 rounded-[var(--rb-r-lg,10px)] px-2.5 py-2 text-left transition-colors duration-150 active:bg-neutral-200 dark:active:bg-neutral-700",
                                  selected
                                    ? "bg-neutral-100 dark:bg-neutral-800"
                                    : "bg-transparent",
                                )}
                              >
                                <Icon
                                  aria-hidden="true"
                                  className="h-4 w-4 shrink-0 text-neutral-500 dark:text-neutral-500"
                                />
                                <span className="min-w-0 flex-1">
                                  <span className="block truncate text-[13px] font-medium text-neutral-900 dark:text-neutral-100">
                                    {command.title}
                                  </span>
                                  {command.detail && (
                                    <span className="block truncate text-xs text-neutral-500 dark:text-neutral-500">
                                      {command.detail}
                                    </span>
                                  )}
                                </span>
                                <Kbd>{command.shortcut}</Kbd>
                              </button>
                            );
                          })}
                        </div>
                      );
                    })}
                  </div>
                ) : (
                  <div className="flex flex-col items-center px-2.5 py-10 text-center">
                    <p className="text-[13px] font-medium text-neutral-900 dark:text-neutral-100">
                      No command matches “{query.trim()}”
                    </p>
                    <p className="mt-1 max-w-xs text-xs text-neutral-600 dark:text-neutral-400">
                      Check the spelling, or clear the search to see everything.
                    </p>
                    <button
                      type="button"
                      onMouseDown={(event) => {
                        event.preventDefault();
                        setQuery("");
                        setActiveIndex(0);
                        inputRef.current?.focus({ preventScroll: true });
                      }}
                      className="mt-4 inline-flex h-8 cursor-pointer items-center justify-center gap-1.5 rounded-[var(--rb-r-md,8px)] bg-neutral-100 px-2.5 text-[13px] font-medium text-neutral-700 transition-[transform,background-color,color] duration-150 ease-[cubic-bezier(0.23,1,0.32,1)] hover:bg-neutral-200 active:scale-[0.97] focus-visible:outline-none focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--rb-accent,oklch(20.5%_0_0))] dark:bg-neutral-800 dark:text-neutral-300 dark:hover:bg-neutral-700 dark:focus-visible:outline-[var(--rb-accent,oklch(100%_0_0))]"
                    >
                      Clear search
                    </button>
                  </div>
                )}
              </div>
              <div
                aria-hidden="true"
                className={cx(
                  "pointer-events-none absolute inset-x-0 top-0 h-8 bg-gradient-to-b from-neutral-50 to-transparent transition-opacity duration-200 ease-out dark:from-neutral-950/50",
                  list.edges.start ? "opacity-100" : "opacity-0",
                )}
              />
              <div
                aria-hidden="true"
                className={cx(
                  "pointer-events-none absolute inset-x-0 bottom-0 h-8 bg-gradient-to-t from-neutral-50 to-transparent transition-opacity duration-200 ease-out dark:from-neutral-950/50",
                  list.edges.end ? "opacity-100" : "opacity-0",
                )}
              />
            </div>

            <div className="flex h-10 shrink-0 items-center gap-4 bg-neutral-50 px-4 text-[11px] text-neutral-500 dark:bg-neutral-950/50 dark:text-neutral-500">
              <span className="hidden items-center gap-1.5 sm:inline-flex">
                <Kbd>↑</Kbd>
                <Kbd>↓</Kbd>
                to navigate
              </span>
              <span className="inline-flex items-center gap-1.5">
                <Kbd>
                  <CornerDownLeft aria-hidden="true" className="h-3 w-3" />
                </Kbd>
                to open
              </span>
              <span className="inline-flex items-center gap-1.5">
                <Kbd>esc</Kbd>
                to close
              </span>
              <span className="ml-auto shrink-0 tabular-nums">
                {filtered.length} {filtered.length === 1 ? "result" : "results"}
              </span>
            </div>
          </div>
        </>
      )}
    </section>
  );
}
