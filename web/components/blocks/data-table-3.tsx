"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { Check, Loader2, Pencil } from "lucide-react";

const cx = (...c: (string | false | null | undefined)[]) =>
  c.filter(Boolean).join(" ");

type SaveState = "idle" | "saving" | "saved";

type Row = {
  id: string;
  workstream: string;
  owner: string;
  hours: number;
  rate: number;
  status: "Draft" | "Needs review" | "Locked";
};

const seed: Row[] = [
  {
    id: "EQ-104",
    workstream: "PT Barito Renewables Energy Tbk",
    owner: "BREN · IDX",
    hours: 12400,
    rate: 9.85,
    status: "Locked",
  },
  {
    id: "PC-118",
    workstream: "Meridian Health Group",
    owner: "Private equity · Fund II",
    hours: 4210,
    rate: 10000,
    status: "Locked",
  },
  {
    id: "PC-121",
    workstream: "Cipta Logistics",
    owner: "Private equity · Fund II",
    hours: 2840,
    rate: 10000,
    status: "Needs review",
  },
  {
    id: "CR-207",
    workstream: "Solus Energy Partners facility",
    owner: "Private credit · Opportunities I",
    hours: 18700,
    rate: 1000,
    status: "Needs review",
  },
  {
    id: "PC-133",
    workstream: "Aruna Payments",
    owner: "Co-invest SPV · Fintech",
    hours: 1230,
    rate: 10000,
    status: "Draft",
  },
  {
    id: "EQ-141",
    workstream: "PT Bank Central Asia Tbk",
    owner: "BBCA · IDX",
    hours: 48000,
    rate: 0.62,
    status: "Locked",
  },
  {
    id: "IN-155",
    workstream: "Helios Data Centers",
    owner: "Infrastructure · Opportunities I",
    hours: 3150,
    rate: 10000,
    status: "Needs review",
  },
  {
    id: "PC-139",
    workstream: "Kirana Consumer Brands",
    owner: "Private equity · Fund II",
    hours: 2290,
    rate: 10000,
    status: "Draft",
  },
  {
    id: "IN-160",
    workstream: "Anchor Re Holdings",
    owner: "Insurance · Fund I",
    hours: 1520,
    rate: 10000,
    status: "Locked",
  },
  {
    id: "DA-170",
    workstream: "wXAUT (Tether Gold)",
    owner: "Digital · Opportunities I",
    hours: 840,
    rate: 3810,
    status: "Draft",
  },
];

const headBase =
  "h-9 px-3 text-xs font-medium text-neutral-500 first:pl-4 sm:first:pl-6 last:pr-4 sm:last:pr-6";
const cellBase =
  "px-3 text-[13px] first:pl-4 sm:first:pl-6 last:pr-4 sm:last:pr-6";

const currency = (n: number) =>
  n.toLocaleString("en-US", {
    style: "currency",
    currency: "USD",
    maximumFractionDigits: 0,
  });

function StatusCell({ status }: { status: Row["status"] }) {
  const dot =
    status === "Needs review"
      ? "bg-amber-500"
      : "bg-neutral-300 dark:bg-neutral-600";
  return (
    <span className="inline-flex items-center gap-1.5 whitespace-nowrap text-[13px] text-neutral-600 dark:text-neutral-400">
      <span
        aria-hidden
        className={cx("h-1.5 w-1.5 shrink-0 rounded-full", dot)}
      />
      {status}
    </span>
  );
}

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

export default function DataTable3() {
  const body = useScrollFade<HTMLDivElement>();
  const [rows, setRows] = useState<Row[]>(seed);
  const [saveState, setSaveState] = useState<Record<string, SaveState>>({});
  const [editing, setEditing] = useState<{
    id: string;
    field: "hours" | "rate";
  } | null>(null);
  const [draft, setDraft] = useState("");
  const timers = useRef<number[]>([]);

  useEffect(() => {
    const t = timers.current;
    return () => t.forEach(clearTimeout);
  }, []);

  const beginEdit = (id: string, field: "hours" | "rate", value: number) => {
    if (rows.find((r) => r.id === id)?.status === "Locked") return;
    setEditing({ id, field });
    setDraft(String(value));
  };

  const commit = () => {
    if (!editing) return;
    const { id, field } = editing;
    const parsed = Math.max(0, Math.round(Number(draft) || 0));
    setRows((prev) =>
      prev.map((r) => (r.id === id ? { ...r, [field]: parsed } : r)),
    );
    setEditing(null);
    setSaveState((s) => ({ ...s, [id]: "saving" }));
    timers.current.push(
      window.setTimeout(() => {
        setSaveState((s) => ({ ...s, [id]: "saved" }));
        timers.current.push(
          window.setTimeout(
            () => setSaveState((s) => ({ ...s, [id]: "idle" })),
            1600,
          ),
        );
      }, 550),
    );
  };

  const total = rows.reduce((sum, r) => sum + r.hours * r.rate, 0);

  const editableCell = (r: Row, field: "hours" | "rate") => {
    const isEditing = editing?.id === r.id && editing.field === field;
    const value = field === "hours" ? r.hours : r.rate;
    const display = field === "hours" ? String(value) : currency(value);
    if (isEditing) {
      return (
        <input
          autoFocus
          type="text"
          inputMode="numeric"
          value={draft}
          onChange={(e) => setDraft(e.target.value.replace(/[^0-9]/g, ""))}
          onBlur={commit}
          onKeyDown={(e) => {
            if (e.key === "Enter") commit();
            if (e.key === "Escape") setEditing(null);
          }}
          aria-label={`Edit ${field} for ${r.workstream}`}
          className="h-7 w-20 rounded-[var(--rb-r-sm,6px)] border border-neutral-200 bg-white px-2 text-right text-[13px] tabular-nums text-neutral-900 transition-colors duration-150 hover:border-neutral-300 focus:border-neutral-900 focus-visible:outline-none focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--rb-accent,oklch(20.5%_0_0))] dark:border-neutral-800 dark:bg-neutral-950 dark:text-neutral-100 dark:hover:border-neutral-700 dark:focus:border-white dark:focus-visible:outline-[var(--rb-accent,oklch(100%_0_0))]"
        />
      );
    }
    if (r.status === "Locked") {
      return (
        <span className="inline-flex h-7 w-20 items-center justify-end px-2 text-[13px] tabular-nums text-neutral-500">
          {display}
        </span>
      );
    }
    return (
      <button
        type="button"
        onClick={() => beginEdit(r.id, field, value)}
        className="group/edit inline-flex h-7 w-20 cursor-pointer items-center justify-end gap-1 rounded-[var(--rb-r-sm,6px)] bg-neutral-100 px-2 text-right text-[13px] tabular-nums text-neutral-900 transition-[transform,background-color,border-color,color] duration-150 ease-[cubic-bezier(0.23,1,0.32,1)] hover:bg-neutral-200 active:scale-[0.97] focus-visible:outline-none focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-[var(--rb-accent,oklch(20.5%_0_0))] dark:bg-neutral-800 dark:text-neutral-100 dark:hover:bg-neutral-700 dark:focus-visible:outline-[var(--rb-accent,oklch(100%_0_0))]"
      >
        <Pencil
          aria-hidden
          className="h-3 w-3 shrink-0 text-neutral-400 opacity-0 transition-opacity duration-150 group-hover/edit:opacity-100"
        />
        {display}
      </button>
    );
  };

  return (
    <div className="relative flex h-full min-h-[640px] w-full flex-col overflow-hidden bg-white dark:bg-neutral-950">
      <div className="flex min-h-0 flex-1 flex-col overflow-hidden">
        <header className="flex h-14 shrink-0 items-center gap-3 bg-neutral-50 px-4 sm:px-6 dark:bg-neutral-900">
          <div className="min-w-0 flex-1">
            <h2 className="truncate text-base font-medium tracking-[-0.01em] text-neutral-900 dark:text-neutral-100">
              Asset register</h2>
            <p className="truncate text-xs text-neutral-600 dark:text-neutral-400">
              Click hours or rate to edit
            </p>
          </div>
        </header>

        <div className="relative min-h-0 flex-1">
          <div
            ref={body.ref}
            onScroll={body.onScroll}
            className="h-full overflow-auto"
          >
            <table className="w-full min-w-[760px] border-collapse text-left">
              <caption className="sr-only">
                Editable workstream budget allocation
              </caption>
              <thead className="sticky top-0 z-20 bg-neutral-50 dark:bg-neutral-900">
                <tr>
                  <th scope="col" className={headBase}>
                    Workstream
                  </th>
                  <th scope="col" className={cx(headBase, "w-[160px]")}>
                    Owner
                  </th>
                  <th scope="col" className={cx(headBase, "w-[150px]")}>
                    Status
                  </th>
                  <th
                    scope="col"
                    className={cx(headBase, "w-[116px] text-right")}
                  >
                    Hours
                  </th>
                  <th
                    scope="col"
                    className={cx(headBase, "w-[116px] text-right")}
                  >
                    Rate
                  </th>
                  <th
                    scope="col"
                    className={cx(headBase, "w-[120px] text-right")}
                  >
                    Total
                  </th>
                </tr>
              </thead>
              <tbody className="divide-y divide-neutral-100 dark:divide-neutral-800/70">
                {rows.map((r) => {
                  const state = saveState[r.id] ?? "idle";
                  return (
                    <tr
                      key={r.id}
                      className="h-11 transition-colors duration-150 hover:bg-neutral-50 dark:hover:bg-neutral-900/60"
                    >
                      <td
                        className={cx(
                          cellBase,
                          "max-w-[280px] font-medium text-neutral-900 dark:text-neutral-100",
                        )}
                      >
                        <div className="flex items-center gap-2">
                          <span className="min-w-0 truncate">
                            {r.workstream}
                          </span>
                          {state === "saving" && (
                            <Loader2
                              aria-label="Saving"
                              className="h-3.5 w-3.5 shrink-0 animate-spin text-neutral-400 motion-reduce:animate-none"
                            />
                          )}
                          {state === "saved" && (
                            <Check
                              aria-label="Saved"
                              className="h-3.5 w-3.5 shrink-0 text-neutral-400"
                            />
                          )}
                        </div>
                      </td>
                      <td
                        className={cx(
                          cellBase,
                          "truncate text-neutral-600 dark:text-neutral-400",
                        )}
                      >
                        {r.owner}
                      </td>
                      <td className={cellBase}>
                        <StatusCell status={r.status} />
                      </td>
                      <td className={cx(cellBase, "text-right")}>
                        {editableCell(r, "hours")}
                      </td>
                      <td className={cx(cellBase, "text-right")}>
                        {editableCell(r, "rate")}
                      </td>
                      <td
                        className={cx(
                          cellBase,
                          "text-right font-medium tabular-nums text-neutral-900 dark:text-neutral-100",
                        )}
                      >
                        {currency(r.hours * r.rate)}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>

          <div
            aria-hidden="true"
            className={cx(
              "pointer-events-none absolute inset-x-0 bottom-0 h-8 bg-gradient-to-t from-white to-transparent transition-opacity duration-200 ease-out dark:from-neutral-950",
              body.edges.end ? "opacity-100" : "opacity-0",
            )}
          />
        </div>

        <footer className="flex h-12 shrink-0 items-center justify-between bg-neutral-50 px-4 sm:px-6 dark:bg-neutral-900">
          <span className="text-xs tabular-nums text-neutral-600 dark:text-neutral-400">
            {rows.length} workstreams
          </span>
          <span className="text-[13px] tabular-nums text-neutral-900 dark:text-neutral-100">
            <span className="text-neutral-500">Total budget </span>
            {currency(total)}
          </span>
        </footer>
      </div>
    </div>
  );
}
