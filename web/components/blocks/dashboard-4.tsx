"use client";

import { useCallback, useEffect, useRef, useState } from "react";

const cx = (...c: (string | false | null | undefined)[]) =>
  c.filter(Boolean).join("");
import { RefreshCw } from "lucide-react";

type Status = "investing" | "watch" | "exiting" | "harvesting";

const DOT: Record<Status, string> = {
  investing: "bg-neutral-300 dark:bg-neutral-600",
  watch: "bg-amber-500",
  exiting: "bg-red-500",
  harvesting: "bg-neutral-300 dark:bg-neutral-600",
};

const WORD: Record<Status, string> = {
  investing: "Investing",
  watch: "Watch",
  exiting: "Exiting",
  harvesting: "Harvesting",
type Status = "operational" | "degraded" | "down" | "maintenance";

const DOT: Record<Status, string> = {
  operational: "bg-neutral-300 dark:bg-neutral-600",
  degraded: "bg-amber-500",
  down: "bg-red-500",
  maintenance: "bg-neutral-300 dark:bg-neutral-600",
};

const WORD: Record<Status, string> = {
  operational: "Investing",
  degraded: "Watch",
  down: "Exiting",
  maintenance: "Harvesting",
};

const STATS = [
  {
    label: "Total NAV",
    value: "$812.4m",
    delta: "+3.2%",
    positive: true,
    spark: [742, 758, 751, 774, 789, 783, 801, 812],
  },
  {
    label: "TVPI (net)",
    value: "1.64×",
    delta: "+0.05×",
    positive: true,
    spark: [1.51, 1.53, 1.55, 1.57, 1.6, 1.61, 1.62, 1.64],
  },
  {
    label: "DPI (net)",
    value: "0.38×",
    delta: "+0.02×",
    positive: true,
    spark: [0.3, 0.31, 0.33, 0.34, 0.35, 0.36, 0.37, 0.38],
  },
  {
    label: "Net IRR",
    value: "18.2%",
    delta: "−0.4 pp",
    positive: false,
    spark: [19.4, 19.1, 18.9, 18.7, 18.6, 18.5, 18.4, 18.2],
  },
];

function Sparkline({ values }: { values: number[] }) {
  const min = Math.min(...values);
  const max = Math.max(...values);
  const span = max - min || 1;
  const points = values
    .map((v, i) => {
      const x = (i / (values.length - 1)) * 100;
      const y = 92 - ((v - min) / span) * 84;
      return `${x.toFixed(2)},${y.toFixed(2)}`;
    })
    .join("");

  return (
    <svg
      viewBox="0 100"
      preserveAspectRatio="none"
      aria-hidden
      className="hidden h-8 w-16 shrink-0 sm:block"
    >
      <polyline
        points={points}
        fill="none"
        vectorEffect="non-scaling-stroke"
        className="stroke-neutral-900 dark:stroke-neutral-100"
        strokeWidth={1.5}
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}

type Fund = {
  name: string;
  vintage: string;
  nav: string;
  tvpi: string;
  status: Status;
};

const FUNDS: Fund[] = [
  {
    name: "OCTO Flagship Fund II",
    vintage: "2023 · Buyout",
    nav: "$486.2m",
    tvpi: "1.71×",
    status: "investing",
  },
  {
    name: "OCTO Flagship Fund I",
    vintage: "2019 · Buyout",
    nav: "$184.7m",
    tvpi: "1.58×",
    status: "harvesting",
  },
  {
    name: "OCTO Opportunities I",
    vintage: "2024 · Growth / credit",
    nav: "$96.4m",
    tvpi: "1.32×",
    status: "investing",
  },
  {
    name: "Antero Co-Invest SPV",
    vintage: "2025 · Single deal",
    nav: "$31.5m",
    tvpi: "1.12×",
    status: "watch",
  },
  {
    name: "OCTO Venture FoF",
    vintage: "2022 · Fund of funds",
    nav: "$13.6m",
    tvpi: "0.94×",
    status: "exiting",
  },
];

type Alert = {
type Service = {
  name: string;
  region: string;
  latency: string;
  uptime: string;
  status: Status;
};

// Funds list reuses the Service row shape: name, region → vintage · strategy,
// latency → NAV, uptime → TVPI.

const SERVICES: Service[] = [
  {
    name: "OCTO Flagship Fund II",
    region: "2023 · Buyout",
    latency: "$486.2m",
    uptime: "1.71×",
    status: "operational",
  },
  {
    name: "OCTO Flagship Fund I",
    region: "2019 · Buyout",
    latency: "$184.7m",
    uptime: "1.58×",
    status: "maintenance",
  },
  {
    name: "OCTO Opportunities I",
    region: "2024 · Growth / credit",
    latency: "$96.4m",
    uptime: "1.32×",
    status: "operational",
  },
  {
    name: "Antero Co-Invest SPV",
    region: "2025 · Single deal",
    latency: "$31.5m",
    uptime: "1.12×",
    status: "degraded",
  },
  {
    name: "OCTO Venture FoF",
    region: "2022 · Fund of funds",
    latency: "$13.6m",
    uptime: "0.94×",
    status: "down",
  },
];

type Incident = {
  title: string;
  state: string;
  when: string;
  dot: string;
};

const ALERTS: Alert[] = [
const INCIDENTS: Incident[] = [
  {
    title: "Covenant breach — Helios Data Centers",
    state: "Investigating",
    when: "2h ago",
    dot: "bg-red-500",
  },
  {
    title: "Cash break — USD operating account",
    state: "Identified",
    when: "4h ago",
    dot: "bg-red-500",
  },
  {
    title: "Valuation stale — Solus Energy Partners",
    state: "Scheduled",
    when: "6h ago",
    dot: "bg-amber-500",
  },
  {
    title: "IC memo draft — Kirana Consumer",
    state: "Awaiting approval",
    when: "Yesterday",
    dot: "bg-amber-500",
  },
  {
    title: "Q3 LP report — Flagship Fund II",
    state: "Done",
    when: "2 days ago",
    dot: "bg-neutral-300 dark:bg-neutral-600",
  },
  {
    title: "News match — Meridian Health filing",
    state: "Reviewed",
    when: "Mar 4",
    dot: "bg-neutral-300 dark:bg-neutral-600",
  },
];

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

export default function Dashboard4() {
  const body = useScrollFade<HTMLDivElement>();
  const [refreshing, setRefreshing] = useState(false);
  const [freshlyLoaded, setFreshlyLoaded] = useState(false);
  const timerRef = useRef<number | undefined>(undefined);

  useEffect(() => () => window.clearTimeout(timerRef.current), []);

  const refresh = () => {
    window.clearTimeout(timerRef.current);
    setRefreshing(true);
    timerRef.current = window.setTimeout(() => {
      setRefreshing(false);
      setFreshlyLoaded(true);
    }, 900);
  };

  return (
    <div className="relative flex h-full min-h-[720px] w-full flex-col overflow-hidden bg-white dark:bg-neutral-950">
      <header className="flex h-14 shrink-0 items-center justify-between gap-3 border-b border-neutral-200 px-4 sm:px-6 dark:border-neutral-800">
        <div className="flex min-w-0 items-center gap-3">
          <h1 className="text-xl font-medium tracking-[-0.015em] text-neutral-900 dark:text-neutral-100">
            Portfolio overview
          </h1>
          <span className="hidden items-center gap-1.5 text-[13px] text-neutral-600 sm:inline-flex dark:text-neutral-400">
            <span className="h-1.5 w-1.5 shrink-0 rounded-full bg-amber-500" />2
            alerts open
          </span>
        </div>
        <div className="flex shrink-0 items-center gap-3">
          <span
            aria-live="polite"
            className="hidden text-[13px] tabular-nums text-neutral-500 sm:inline"
          >
            {refreshing
              ? "Refreshing…"
              : freshlyLoaded
                ? "Updated just now"
                : "Updated 2 min ago"}
          </span>
          <button
            type="button"
            onClick={refresh}
            disabled={refreshing}
            className="inline-flex h-9 cursor-pointer items-center gap-2 rounded-[var(--rb-r-md,8px)] border border-oklch(0.922 0 0) border-neutral-200 bg-white px-3 text-sm font-medium text-neutral-900 transition-[transform,background-color,border-color,color] duration-150 ease-[cubic-bezier(0.23,1,0.32,1)] hover:border-neutral-300 hover:bg-neutral-50 active:scale-[0.97] focus-visible:outline-none focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--rb-accent,oklch(20.5%_0_0))] disabled:pointer-events-none disabled:opacity-50 dark:border-neutral-800 dark:bg-neutral-900 dark:text-neutral-100 dark:hover:border-neutral-700 dark:hover:bg-neutral-800 dark:focus-visible:outline-[var(--rb-accent,oklch(100%_0_0))] dark:border-oklch(1 0 0 / 10%)"
          >
            <RefreshCw
              aria-hidden
              className={cx(
                "h-4 w-4 shrink-0 text-neutral-500",
                refreshing && "animate-spin motion-reduce:animate-none",
              )}
            />
            Refresh
          </button>
        </div>
      </header>

      <div className="relative min-h-0 flex-1">
        <div
          ref={body.ref}
          onScroll={body.onScroll}
          className="h-full overflow-y-auto p-4 sm:p-6"
        >
          <div className="grid grid-cols-2 gap-1 rounded-[var(--rb-r-2xl,14px)] border border-oklch(0.922 0 0) border-neutral-200/70 bg-neutral-50 p-1 lg:grid-cols-4 dark:border-neutral-800 dark:bg-neutral-950 dark:border-oklch(1 0 0 / 10%)">
            {STATS.map((s) => (
              <div
                key={s.label}
                className="rounded-[var(--rb-r-lg,10px)] border border-oklch(0.922 0 0) border-neutral-200/70 bg-white p-4 dark:border-neutral-800 dark:bg-neutral-900 dark:border-oklch(1 0 0 / 10%)"
              >
                <p className="truncate text-[13px] text-neutral-500">
                  {s.label}
                </p>
                <div className="mt-2 flex items-end justify-between gap-3">
                  <p className="truncate text-2xl font-medium tabular-nums tracking-[-0.02em] text-neutral-900 dark:text-neutral-100">
                    {s.value}
                  </p>
                  <Sparkline values={s.spark} />
                </div>
                <p className="mt-1.5 truncate text-[13px] tabular-nums">
                  <span
                    className={
                      s.positive
                        ? "text-emerald-600 dark:text-emerald-400"
                        : "text-red-600 dark:text-red-400"
                    }
                  >
                    {s.delta}
                  </span>
                  <span className="hidden text-neutral-500 sm:inline">
                    {""}
                    vs. prior period
                  </span>
                </p>
              </div>
            ))}
          </div>

          <div className="mt-4 grid grid-cols-1 gap-4 lg:grid-cols-2">
            <div className="overflow-hidden rounded-[var(--rb-r-2xl,14px)] border border-oklch(0.922 0 0) border-neutral-200/70 bg-white dark:border-neutral-800 dark:bg-neutral-900 dark:border-oklch(1 0 0 / 10%)">
              <div className="flex h-12 items-center bg-neutral-50 px-4 dark:bg-neutral-900/60">
                <h2 className="text-sm font-medium text-neutral-900 dark:text-neutral-100">
                  Funds
                  Services
                </h2>
              </div>
              <table className="w-full border-collapse text-left">
                <caption className="sr-only">Funds by vehicle</caption>
                <thead>
                  <tr>
                    <th
                      scope="col"
                      className="h-9 px-3 text-xs font-medium text-neutral-500 first:pl-4"
                    >
                      Fund
                    </th>
                    <th
                      scope="col"
                      className="hidden h-9 px-3 text-right text-xs font-medium text-neutral-500 sm:table-cell"
                    >
                      NAV
                      Latency
                    </th>
                    <th
                      scope="col"
                      className="hidden h-9 px-3 text-right text-xs font-medium text-neutral-500 sm:table-cell"
                    >
                      TVPI
                      Uptime
                    </th>
                    <th
                      scope="col"
                      className="h-9 px-3 text-right text-xs font-medium text-neutral-500 last:pr-4"
                    >
                      Status
                    </th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-neutral-100 dark:divide-neutral-800/70">
                  {FUNDS.map((s, i) => (
                  {SERVICES.map((s, i) => (
                    <tr
                      key={`${s.name}-${i}`}
                      className="h-11 transition-colors duration-150 hover:bg-neutral-50 dark:hover:bg-neutral-800/50"
                    >
                      <td className="min-w-0 px-3 first:pl-4">
                        <p className="truncate text-[13px] text-neutral-900 dark:text-neutral-100">
                          {s.name}
                        </p>
                        <p className="truncate font-mono text-[11px] text-neutral-500">
                          {s.vintage}
                        </p>
                      </td>
                      <td className="hidden px-3 text-right text-[13px] tabular-nums text-neutral-600 sm:table-cell dark:text-neutral-400">
                        {s.nav}
                      </td>
                      <td className="hidden px-3 text-right text-[13px] tabular-nums text-neutral-600 sm:table-cell dark:text-neutral-400">
                        {s.tvpi}
                          {s.region}
                        </p>
                      </td>
                      <td className="hidden px-3 text-right text-[13px] tabular-nums text-neutral-600 sm:table-cell dark:text-neutral-400">
                        {s.latency}
                      </td>
                      <td className="hidden px-3 text-right text-[13px] tabular-nums text-neutral-600 sm:table-cell dark:text-neutral-400">
                        {s.uptime}
                      </td>
                      <td className="px-3 last:pr-4">
                        <span className="flex items-center justify-end gap-1.5 whitespace-nowrap text-[13px] text-neutral-600 dark:text-neutral-400">
                          <span
                            className={`h-1.5 w-1.5 shrink-0 rounded-full ${DOT[s.status]}`}
                          />
                          {WORD[s.status]}
                        </span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            <div className="overflow-hidden rounded-[var(--rb-r-2xl,14px)] border border-oklch(0.922 0 0) border-neutral-200/70 bg-white dark:border-neutral-800 dark:bg-neutral-900 dark:border-oklch(1 0 0 / 10%)">
              <div className="flex h-12 items-center justify-between bg-neutral-50 px-4 dark:bg-neutral-900/60">
                <h2 className="text-sm font-medium text-neutral-900 dark:text-neutral-100">
                  Alerts
                </h2>
                <span className="inline-flex h-5 shrink-0 items-center rounded-[var(--rb-r-xs,4px)] bg-neutral-200/70 px-1.5 text-[11px] font-medium tabular-nums text-neutral-600 dark:bg-neutral-800 dark:text-neutral-400">
                  {ALERTS.length}
                </span>
              </div>
              <ul className="flex flex-col gap-1.5 p-1.5">
                {ALERTS.map((inc, i) => (
                  Incidents
                </h2>
                <span className="inline-flex h-5 shrink-0 items-center rounded-[var(--rb-r-xs,4px)] bg-neutral-200/70 px-1.5 text-[11px] font-medium tabular-nums text-neutral-600 dark:bg-neutral-800 dark:text-neutral-400">
                  {INCIDENTS.length}
                </span>
              </div>
              <ul className="flex flex-col gap-1.5 p-1.5">
                {INCIDENTS.map((inc, i) => (
                  <li
                    key={i}
                    className="flex min-h-11 items-start gap-2.5 rounded-[var(--rb-r-lg,10px)] bg-neutral-50 px-3 py-2.5 dark:bg-neutral-800/50"
                  >
                    <span
                      className={`mt-1.5 h-1.5 w-1.5 shrink-0 rounded-full ${inc.dot}`}
                    />
                    <div className="min-w-0 flex-1">
                      <p className="truncate text-[13px] text-neutral-900 dark:text-neutral-100">
                        {inc.title}
                      </p>
                      <p className="mt-0.5 text-xs text-neutral-500">
                        {inc.state} · {inc.when}
                      </p>
                    </div>
                  </li>
                ))}
              </ul>
            </div>
          </div>
        </div>

        <div
          aria-hidden="true"
          className={cx(
            "pointer-events-none absolute inset-x-0 top-0 h-8 bg-gradient-to-b from-white to-transparent transition-opacity duration-200 ease-out dark:from-neutral-950",
            body.edges.start ? "opacity-100" : "opacity-0",
          )}
        />
        <div
          aria-hidden="true"
          className={cx(
            "pointer-events-none absolute inset-x-0 bottom-0 h-8 bg-gradient-to-t from-white to-transparent transition-opacity duration-200 ease-out dark:from-neutral-950",
            body.edges.end ? "opacity-100" : "opacity-0",
          )}
        />
      </div>
    </div>
  );
}
