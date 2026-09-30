"use client";

import { useCallback, useEffect, useState } from "react";
import { Loader2, UserPlus } from "lucide-react";
import PanelHeader from "@/components/panel-header";
import { getJson, messageFor, postJson } from "@/lib/api";
import { useTenants } from "@/lib/use-tenants";

/**
 * Deal pipeline, live: `GET /api/v1/prospects?tenantId&stage` per column
 * (ProspectView wire shape), register through `POST /api/v1/prospects`, and
 * forward transitions through `POST /api/v1/prospects/{id}/transition` — the
 * server's state machine owns what is legal; a 409 tells the user the move
 * was refused, nothing here pre-validates.
 */

const cx = (...c: (string | false | null | undefined)[]) =>
  c.filter(Boolean).join(" ");

type Prospect = {
  id: string;
  name: string;
  source: string;
  sector: string | null;
  region: string | null;
  stage: string;
  registeredAt: string;
  lastEventAt: string;
};

const STAGES = [
  { id: "sourced", name: "Sourced" },
  { id: "screening", name: "Screening" },
  { id: "due-diligence", name: "Due diligence" },
  { id: "ic-review", name: "IC review" },
] as const;

const NEXT: Record<string, string> = {
  sourced: "screening",
  screening: "due-diligence",
  "due-diligence": "ic-review",
};

// ic-review is terminal for the forward path: advancing to `invested` needs an
// approved IC approval task id, which lives in the approvals flow, not here.
// The reachable terminal move from any non-terminal stage is `passed` — it
// only needs a rationale, so the pipeline is no longer a dead end (#36).

const STAGE_TONE: Record<string, string> = {
  sourced: "bg-neutral-300 dark:bg-neutral-600",
  screening: "bg-amber-500",
  "due-diligence": "bg-amber-500",
  "ic-review": "bg-red-500",
};

export default function PipelineBoard() {
  const { tenantId } = useTenants();
  const [cols, setCols] = useState<Record<string, Prospect[]>>({});
  const [loading, setLoading] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [moving, setMoving] = useState<string | null>(null);
  const [composing, setComposing] = useState(false);
  const [name, setName] = useState("");

  const load = useCallback(async () => {
    if (!tenantId) return;
    setLoading(true);
    setError(null);
    try {
      const results = await Promise.all(
        STAGES.map(async (s) => {
          const list = await getJson<Prospect[]>(
            `/api/v1/prospects?tenantId=${tenantId}&stage=${s.id}&limit=200`,
          );
          return [s.id, list] as const;
        }),
      );
      setCols(Object.fromEntries(results));
      setLoaded(true);
    } catch (e) {
      setError(messageFor(e));
    } finally {
      setLoading(false);
    }
  }, [tenantId]);

  useEffect(() => {
    load();
  }, [load]);

  const advance = async (p: Prospect) => {
    const to = NEXT[p.stage];
    if (!to || moving) return;
    setMoving(p.id);
    setNotice(null);
    try {
      await postJson(`/api/v1/prospects/${p.id}/transition`, { to });
      await load();
    } catch (e) {
      setError(messageFor(e));
    } finally {
      setMoving(null);
    }
  };

  const pass = async (p: Prospect) => {
    if (moving) return;
    const rationale = window.prompt(`Why is ${p.name} being passed?`)?.trim();
    if (!rationale) return;
    setMoving(p.id);
    setNotice(null);
    try {
      await postJson(`/api/v1/prospects/${p.id}/transition`, { to: "passed", rationale });
      setNotice(`${p.name} passed.`);
      await load();
    } catch (e) {
      setError(messageFor(e));
    } finally {
      setMoving(null);
    }
  };

  const register = async () => {
    const trimmed = name.trim();
    if (!trimmed || !tenantId) return;
    setNotice(null);
    try {
      await postJson("/api/v1/prospects", {
        tenantId,
        name: trimmed,
        source: "manual",
      });
      setName("");
      setComposing(false);
      setNotice(`${trimmed} registered in Sourced.`);
      await load();
    } catch (e) {
      setError(messageFor(e));
    }
  };

  return (
    // Fixed-height board with per-column scroll only when all four columns sit
    // side by side; stacked columns take their natural height and the page scrolls.
    <div className="flex flex-col xl:h-full xl:min-h-[560px]">
      <PanelHeader
        description="Live from the prospect ledger. Advance moves through the state machine; the server rejects illegal jumps."
        onRefresh={load}
        refreshing={loading}
        refreshLabel="Refresh pipeline"
        error={error}
        notice={notice}
      />

      <div className="grid grid-cols-1 gap-2.5 sm:grid-cols-2 xl:min-h-0 xl:flex-1 xl:grid-cols-4">

      <div className="grid grid-cols-1 gap-2.5 sm:grid-cols-2 xl:min-h-0 xl:flex-1 xl:grid-cols-4">
    <div className="flex h-full min-h-[680px] flex-col bg-white dark:bg-neutral-950">
      <header className="flex shrink-0 flex-wrap items-center gap-3 px-6 pt-6 pb-4 sm:px-8">
        <div className="min-w-0 flex-1">
          <h2 className="text-base font-medium tracking-[-0.01em] text-neutral-900 dark:text-neutral-100">
            Deal pipeline
          </h2>
          <p className="mt-0.5 text-[13px] text-neutral-500">
            Live from the prospect ledger. Advance moves through the state
            machine; the server rejects illegal jumps.
          </p>
        </div>
        <button
          type="button"
          onClick={load}
          aria-label="Refresh pipeline"
          className="inline-flex h-8 w-8 shrink-0 cursor-pointer items-center justify-center rounded-[var(--rb-r-md,8px)] border border-neutral-200/70 text-neutral-600 hover:bg-neutral-50 dark:border-neutral-800 dark:text-neutral-400 dark:hover:bg-neutral-900"
        >
          <RefreshCw
            aria-hidden
            className={cx("h-4 w-4", loading && "animate-spin motion-reduce:animate-none")}
          />
        </button>
      </header>

      {error && (
        <p role="alert" className="mx-6 mb-2 text-[13px] text-red-600 dark:text-red-400">
          {error}
        </p>
      )}
      {notice && (
        <p className="mx-6 mb-2 text-[13px] text-emerald-600 dark:text-emerald-400">
          {notice}
        </p>
      )}

      <div className="grid min-h-0 flex-1 grid-cols-1 gap-2.5 overflow-y-auto px-6 pb-6 sm:grid-cols-2 sm:px-8 xl:grid-cols-4">
        {STAGES.map((stage) => {
          const rows = cols[stage.id] ?? [];
          return (
            <section
              key={stage.id}
              aria-label={stage.name}
              className="flex min-h-0 flex-col rounded-[var(--rb-r-2xl,14px)] border border-neutral-200/70 bg-neutral-50 p-1 dark:border-neutral-800 dark:bg-neutral-950"
            >
              <div className="flex h-8 shrink-0 items-center gap-2 px-2">
                <span className="truncate text-[13px] font-medium text-neutral-900 dark:text-neutral-100">
                  {stage.name}
                </span>
                <span className="text-[11px] tabular-nums text-neutral-500">
                  {rows.length}
                </span>
                {stage.id === "sourced" && (
                  <button
                    type="button"
                    onClick={() => setComposing((v) => !v)}
                    aria-label="Register a prospect"
                    className="ml-auto inline-flex h-6 w-6 cursor-pointer items-center justify-center rounded-[var(--rb-r-sm,6px)] text-neutral-500 hover:bg-neutral-200/70 hover:text-neutral-900 dark:hover:bg-neutral-800 dark:hover:text-neutral-100"
                  >
                    <UserPlus aria-hidden className="h-3.5 w-3.5" />
                  </button>
                )}
              </div>

              {composing && stage.id === "sourced" && (
                <div className="mb-1 rounded-[var(--rb-r-lg,10px)] border border-neutral-200/70 bg-white p-2 dark:border-neutral-800 dark:bg-neutral-900">
                  <input
                    autoFocus
                    aria-label="Prospect name"
                    value={name}
                    onChange={(e) => setName(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === "Enter") register();
                      if (e.key === "Escape") setComposing(false);
                    }}
                    placeholder="Prospect name…"
                    className="h-8 w-full rounded-[var(--rb-r-sm,6px)] bg-transparent px-1 text-[13px] text-neutral-900 placeholder:text-neutral-400 dark:text-neutral-100"
                  />
                  <button
                    type="button"
                    onClick={register}
                    className="mt-1 inline-flex h-7 cursor-pointer items-center rounded-[var(--rb-r-sm,6px)] bg-[var(--rb-accent,oklch(20.5%_0_0))] px-2.5 text-[12px] font-medium text-[var(--rb-accent-fg,oklch(100%_0_0))] dark:bg-[var(--rb-accent,oklch(100%_0_0))] dark:text-[var(--rb-accent-fg,oklch(20.5%_0_0))]"
                  >
                    Register
                  </button>
                </div>
              )}

              <ul className="min-h-0 flex-1 space-y-1 overflow-y-auto p-1 pt-0">
                {rows.map((p) => (
                  <li
                    key={p.id}
                    className="rounded-[var(--rb-r-lg,10px)] border border-neutral-200/70 bg-white p-2.5 dark:border-neutral-800 dark:bg-neutral-900"
                  >
                    <div className="flex items-center gap-1.5">
                      <span
                        aria-hidden
                        className={cx(
                          "h-1.5 w-1.5 shrink-0 rounded-full",
                          STAGE_TONE[p.stage] ?? STAGE_TONE.sourced,
                        )}
                      />
                      <span className="truncate text-[13px] font-medium text-neutral-900 dark:text-neutral-100">
                        {p.name}
                      </span>
                    </div>
                    <p className="mt-1 truncate text-[11px] text-neutral-500">
                      {[p.sector, p.region, p.source].filter(Boolean).join(" · ") || "—"}
                    </p>
                    <div className="mt-1.5 flex flex-wrap gap-1.5">
                      {NEXT[p.stage] && (
                        <button
                          type="button"
                          disabled={moving !== null}
                          onClick={() => advance(p)}
                          className="inline-flex h-6 cursor-pointer items-center rounded-[var(--rb-r-sm,6px)] bg-neutral-100 px-2 text-[11px] font-medium text-neutral-700 hover:bg-neutral-200 disabled:opacity-50 dark:bg-neutral-800 dark:text-neutral-300 dark:hover:bg-neutral-700"
                        >
                          {moving === p.id ? (
                            <Loader2 aria-hidden className="h-3 w-3 animate-spin motion-reduce:animate-none" />
                          ) : (
                            `Advance to ${STAGES.find((s) => s.id === NEXT[p.stage])?.name}`
                          )}
                        </button>
                      )}
                      <button
                        type="button"
                        disabled={moving !== null}
                        onClick={() => pass(p)}
                        className="inline-flex h-6 cursor-pointer items-center rounded-[var(--rb-r-sm,6px)] px-2 text-[11px] font-medium text-neutral-500 hover:bg-neutral-100 hover:text-red-600 disabled:opacity-50 dark:text-neutral-400 dark:hover:bg-neutral-800 dark:hover:text-red-400"
                      >
                        Pass
                      </button>
                    </div>
                  </li>
                ))}
                {loaded && !loading && rows.length === 0 && (
                  <p className="px-2 py-6 text-center text-[12px] text-neutral-400 dark:text-neutral-600">
                    Nothing here yet
                  </p>
                )}
                {!loaded && !error && rows.length === 0 && (
                  <p className="px-2 py-6 text-center text-[12px] text-neutral-400 dark:text-neutral-600">
                    Loading…
                  </p>
                )}
              </ul>
            </section>
          );
        })}
      </div>
    </div>
  );
}
