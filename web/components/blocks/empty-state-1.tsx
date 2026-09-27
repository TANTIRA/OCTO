"use client";

import {
  ArrowRight,
  Database,
  FileSpreadsheet,
  Plug,
  Plus,
} from "lucide-react";

const cx = (...c: (string | false | null | undefined)[]) =>
  c.filter(Boolean).join("");

const focus =
  "focus-visible:outline-none focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-[var(--rb-accent,oklch(20.5%_0_0))] dark:focus-visible:outline-[var(--rb-accent,oklch(100%_0_0))]";

const transition =
  "transition-[background-color,border-color,color,opacity,transform] duration-150 ease-out";

const frame =
  "rounded-[var(--rb-r-2xl,14px)] border border-oklch(0.922 0 0) border-neutral-200/70 bg-neutral-50 p-1 dark:border-neutral-800 dark:bg-neutral-950 dark:border-oklch(1 0 0 / 10%)";
const panel =
  "rounded-[var(--rb-r-lg,10px)] border border-oklch(0.922 0 0) border-neutral-200/70 bg-white dark:border-neutral-800 dark:bg-neutral-900 dark:border-oklch(1 0 0 / 10%)";

const STARTERS = [
  {
    icon: Plug,
    title: "Connect a data source",
    body: "Postgres, BigQuery or Snowflake. Read-only credentials are enough.",
    meta: "About 2 minutes",
  },
  {
    icon: FileSpreadsheet,
    title: "Upload a spreadsheet",
    body: "Drop in a CSV and we will infer the column types for you.",
    meta: "No setup",
  },
  {
    icon: Database,
    title: "Load the sample dataset",
    body: "A fictional storefront with 18 months of orders to explore.",
    meta: "Instant",
  },
];

const GHOST_ROWS = [
  [72, 40, 28],
  [56, 52, 36],
  [64, 34, 44],
  [48, 46, 30],
];

export default function EmptyState1() {
  return (
    <div className="flex h-full min-h-[640px] w-full flex-col overflow-y-auto bg-white p-4 sm:p-6 dark:bg-neutral-950">
      <div className="mx-auto flex w-full max-w-[720px] flex-1 flex-col justify-center">
        <div className="relative">
          <div
            aria-hidden
            className={cx(frame, "pointer-events-none select-none")}
          >
            <div className={cx(panel, "space-y-1 p-1")}>
              {GHOST_ROWS.map((row, i) => (
                <div
                  key={i}
                  className="flex items-center gap-3 rounded-[var(--rb-r-sm,6px)] px-3 py-2.5"
                  style={{ opacity: 1 - i * 0.22 }}
                >
                  <span className="h-5 w-5 shrink-0 rounded-[var(--rb-r-sm,6px)] bg-neutral-100 dark:bg-neutral-800" />
                  {row.map((w, j) => (
                    <span
                      key={j}
                      className="h-2 rounded-full bg-neutral-100 dark:bg-neutral-800"
                      style={{ width: `${w}px` }}
                    />
                  ))}
                  <span className="ml-auto h-2 w-10 rounded-full bg-neutral-100 dark:bg-neutral-800" />
                </div>
              ))}
            </div>
          </div>
          <div
            aria-hidden
            className="pointer-events-none absolute inset-x-0 bottom-0 h-24 bg-gradient-to-b from-transparent to-white dark:to-neutral-950"
          />
        </div>

        <div className="mt-6 text-center">
          <h2 className="text-[19px] font-medium tracking-[-0.01em] text-neutral-900 sm:text-xl dark:text-neutral-100">
            Nothing in this workspace yet
          </h2>
          <p className="mx-auto mt-1.5 max-w-[420px] text-[13px] leading-relaxed text-neutral-500">
            Point OCTO at a custodian feed and positions reconcile themselves.
            You can change or remove a source at any time.
          </p>
        </div>

        <div className={cx(frame, "mt-5 grid gap-1 sm:grid-cols-3")}>
          {STARTERS.map(({ icon: Icon, title, body, meta }) => (
            <button
              key={title}
              type="button"
              className={cx(
                panel,
                "group cursor-pointer p-4 text-left hover:border-neutral-300 hover:bg-neutral-50 dark:hover:border-neutral-700 dark:hover:bg-neutral-800",
                transition,
                focus,
              )}
            >
              <span
                aria-hidden
                className="flex h-8 w-8 items-center justify-center rounded-[var(--rb-r-md,8px)] border border-oklch(0.922 0 0) border-neutral-200 dark:border-neutral-800 dark:border-oklch(1 0 0 / 10%)"
              >
                <Icon className="h-4 w-4 text-neutral-700 dark:text-neutral-300" />
              </span>
              <span className="mt-3 flex items-center gap-1 text-[13px] font-medium text-neutral-900 dark:text-neutral-100">
                {title}
                <ArrowRight
                  className="h-3 w-3 -translate-x-1 opacity-0 transition-[opacity,transform] duration-150 ease-out group-hover:translate-x-0 group-hover:opacity-100"
                  aria-hidden
                />
              </span>
              <span className="mt-1 block text-[12px] leading-relaxed text-neutral-500">
                {body}
              </span>
              <span className="mt-2 block text-[11px] tracking-[0.06em] text-neutral-400 uppercase">
                {meta}
              </span>
            </button>
          ))}
        </div>

        <div className="mt-5 flex flex-col items-center gap-2 sm:flex-row sm:justify-center">
          <button
            type="button"
            className={cx(
              "inline-flex h-9 w-full cursor-pointer items-center justify-center gap-1.5 rounded-[var(--rb-r-md,8px)] bg-[var(--rb-accent,oklch(20.5%_0_0))] px-4 text-[13px] font-medium text-[var(--rb-accent-fg,oklch(100%_0_0))] hover:bg-[color-mix(in_oklab,var(--rb-accent,oklch(20.5%_0_0))_90%,transparent)] sm:w-auto dark:bg-[var(--rb-accent,oklch(100%_0_0))] dark:text-[var(--rb-accent-fg,oklch(20.5%_0_0))] dark:hover:bg-[color-mix(in_oklab,var(--rb-accent,oklch(100%_0_0))_90%,transparent)]",
              transition,
              focus,
            )}
          >
            <Plus className="h-3.5 w-3.5" aria-hidden />
            Add a source
          </button>
          <button
            type="button"
            className={cx(
              "inline-flex h-9 w-full cursor-pointer items-center justify-center rounded-[var(--rb-r-md,8px)] px-3 text-[13px] font-medium text-neutral-600 hover:bg-neutral-100 hover:text-neutral-900 sm:w-auto dark:text-neutral-300 dark:hover:bg-neutral-800 dark:hover:text-neutral-100",
              transition,
              focus,
            )}
          >
            Read the setup guide
          </button>
        </div>
      </div>
    </div>
  );
}
