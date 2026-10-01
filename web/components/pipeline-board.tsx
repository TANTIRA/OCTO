"use client";

import { useCallback, useEffect, useState } from "react";
import { Loader2, UserPlus } from "lucide-react";
import PanelHeader from "@/components/panel-header";
import { ApiError, getJson, messageFor, postJson } from "@/lib/api";
import { supabase } from "@/lib/supabase";
import { useTenants } from "@/lib/use-tenants";

/**
 * Deal pipeline, live: `GET /api/v1/prospects?tenantId&stage` per column
 * (ProspectView wire shape), register through `POST /api/v1/prospects`, and
 * forward transitions through `POST /api/v1/prospects/{id}/transition` — the
 * server's state machine owns what is legal; a 409 tells the user the move
 * was refused, nothing here pre-validates.
 *
 * IC review cards run the IC gate (#334): request the approval
 * (`POST …/ic-review`), read it back (`GET …/ic-review`), let an approver or
 * admin who did not request it decide (`POST …/tasks/{taskId}`), and once it
 * is approved move to `invested` with that task id. The buttons only mirror
 * what the API will accept; segregation of duties is enforced server-side.
 */

const cx = (...c: (string | false | null | undefined)[]) =>
  c.filter(Boolean).join(" ");

type IcReview = {
  taskId: string;
  taskStatus: string;
  requestedBy: string;
  decidedBy: string | null;
};

// The rationale prompt open on one card: passing, rejecting the IC review, or
// recording the investment — each needs a reason on the audit trail.
type Prompt = { id: string; action: "pass" | "reject" | "invest" };

const PROMPT_COPY: Record<Prompt["action"], { placeholder: string; confirm: string }> = {
  pass: { placeholder: "Why pass? Recorded on the audit trail.", confirm: "Confirm pass" },
  reject: { placeholder: "Why reject? Recorded on the IC task.", confirm: "Confirm reject" },
  invest: { placeholder: "Investment rationale — recorded on the audit trail.", confirm: "Confirm invested" },
};

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

// ic-review has no plain "advance": `invested` needs an approved IC approval
// task id, so those cards run the IC gate below instead. `passed` stays
// reachable from every non-terminal stage with a rationale (#36).

const STAGE_TONE: Record<string, string> = {
  sourced: "bg-neutral-300 dark:bg-neutral-600",
  screening: "bg-amber-500",
  "due-diligence": "bg-amber-500",
  "ic-review": "bg-red-500",
};

export default function PipelineBoard() {
  const { tenantId, tenants } = useTenants();
  const role = tenants.find((t) => t.tenantId === tenantId)?.role;
  const canWrite = role !== undefined && role !== "viewer";
  const canDecide = role === "approver" || role === "admin";
  const [cols, setCols] = useState<Record<string, Prospect[]>>({});
  const [loading, setLoading] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [moving, setMoving] = useState<string | null>(null);
  const [composing, setComposing] = useState(false);
  const [name, setName] = useState("");
  const [prompt, setPrompt] = useState<Prompt | null>(null);
  const [rationale, setRationale] = useState("");
  // IC review per ic-review prospect: undefined until read, null when none exists.
  const [ic, setIc] = useState<Record<string, IcReview | null>>({});
  const [me, setMe] = useState<string | null>(null);

  useEffect(() => {
    supabase?.auth.getSession().then(({ data }) => setMe(data.session?.user.id ?? null));
  }, []);

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
      const atIc = results.find(([stage]) => stage === "ic-review")?.[1] ?? [];
      const reviews = await Promise.all(
        atIc.map(async (p) => {
          try {
            return [p.id, await getJson<IcReview>(`/api/v1/prospects/${p.id}/ic-review`)] as const;
          } catch (e) {
            if (e instanceof ApiError && e.status === 404) return [p.id, null] as const;
            throw e;
          }
        }),
      );
      setIc(Object.fromEntries(reviews));
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

  // One write per card at a time; every outcome reloads from the server.
  const act = async (p: Prospect, write: () => Promise<unknown>, done: string) => {
    if (moving) return;
    setMoving(p.id);
    setNotice(null);
    setError(null);
    try {
      await write();
      setPrompt(null);
      setNotice(done);
      await load();
    } catch (e) {
      setError(messageFor(e));
    } finally {
      setMoving(null);
    }
  };

  const confirmPrompt = (p: Prospect) => {
    const why = rationale.trim();
    if (!prompt || !why) return;
    const review = ic[p.id];
    if (prompt.action === "pass") {
      act(p, () => postJson(`/api/v1/prospects/${p.id}/transition`, { to: "passed", rationale: why }), `${p.name} passed.`);
    } else if (prompt.action === "reject" && review) {
      act(
        p,
        () => postJson(`/api/v1/prospects/${p.id}/tasks/${review.taskId}`, { event: "rejected", rationale: why }),
        `IC review for ${p.name} rejected.`,
      );
    } else if (prompt.action === "invest" && review) {
      act(
        p,
        () =>
          postJson(`/api/v1/prospects/${p.id}/transition`, { to: "invested", rationale: why, taskId: review.taskId }),
        `${p.name} recorded as invested.`,
      );
    }
  };

  const openPrompt = (id: string, action: Prompt["action"]) => {
    setRationale("");
    setPrompt({ id, action });
  };

  const register = async () => {
    const trimmed = name.trim();
    if (!trimmed || !tenantId || moving) return;
    setMoving("register");
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
    } finally {
      setMoving(null);
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
                {stage.id === "sourced" && canWrite && (
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

              {composing && canWrite && stage.id === "sourced" && (
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
                    disabled={!name.trim() || moving !== null}
                    className="mt-1 inline-flex h-7 cursor-pointer items-center rounded-[var(--rb-r-sm,6px)] bg-[var(--rb-accent,oklch(20.5%_0_0))] px-2.5 text-[12px] font-medium text-[var(--rb-accent-fg,oklch(100%_0_0))] disabled:cursor-default disabled:opacity-50 dark:bg-[var(--rb-accent,oklch(100%_0_0))] dark:text-[var(--rb-accent-fg,oklch(20.5%_0_0))]"
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
                    {prompt?.id === p.id ? (
                      <form
                        onSubmit={(e) => {
                          e.preventDefault();
                          confirmPrompt(p);
                        }}
                        className="mt-1.5 space-y-1.5"
                      >
                        <input
                          autoFocus
                          aria-label={`${PROMPT_COPY[prompt.action].confirm} — ${p.name}`}
                          value={rationale}
                          onChange={(e) => setRationale(e.target.value)}
                          onKeyDown={(e) => e.key === "Escape" && setPrompt(null)}
                          placeholder={PROMPT_COPY[prompt.action].placeholder}
                          className="h-7 w-full rounded-[var(--rb-r-sm,6px)] border border-neutral-200/70 bg-white px-2 text-[12px] text-neutral-900 placeholder:text-neutral-400 dark:border-neutral-700 dark:bg-neutral-950 dark:text-neutral-100"
                        />
                        <div className="flex gap-1.5">
                          <button
                            type="submit"
                            disabled={!rationale.trim() || moving !== null}
                            className={cx(
                              "inline-flex h-6 cursor-pointer items-center rounded-[var(--rb-r-sm,6px)] px-2 text-[11px] font-medium text-white disabled:cursor-default disabled:opacity-50",
                              prompt.action === "invest" ? "bg-emerald-600 hover:bg-emerald-700" : "bg-red-600 hover:bg-red-700",
                            )}
                          >
                            {moving === p.id ? (
                              <Loader2 aria-hidden className="h-3 w-3 animate-spin motion-reduce:animate-none" />
                            ) : (
                              PROMPT_COPY[prompt.action].confirm
                            )}
                          </button>
                          <button
                            type="button"
                            onClick={() => setPrompt(null)}
                            className="inline-flex h-6 cursor-pointer items-center rounded-[var(--rb-r-sm,6px)] px-2 text-[11px] font-medium text-neutral-500 hover:bg-neutral-100 dark:hover:bg-neutral-800"
                          >
                            Cancel
                          </button>
                        </div>
                      </form>
                    ) : canWrite ? (
                      // Viewers get no write actions: the API answers every
                      // prospect write from a VIEWER with 404 (#506).
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
                        {p.stage === "ic-review" && (
                          <IcActions
                            review={ic[p.id]}
                            busy={moving !== null}
                            canDecide={canDecide}
                            me={me}
                            onRequest={() =>
                              act(p, () => postJson(`/api/v1/prospects/${p.id}/ic-review`, {}), `IC review requested for ${p.name}.`)
                            }
                            onApprove={(taskId) =>
                              act(
                                p,
                                () => postJson(`/api/v1/prospects/${p.id}/tasks/${taskId}`, { event: "approved" }),
                                `IC review for ${p.name} approved.`,
                              )
                            }
                            onReject={() => openPrompt(p.id, "reject")}
                            onInvest={() => openPrompt(p.id, "invest")}
                          />
                        )}
                        <button
                          type="button"
                          disabled={moving !== null}
                          onClick={() => openPrompt(p.id, "pass")}
                          className="inline-flex h-6 cursor-pointer items-center rounded-[var(--rb-r-sm,6px)] px-2 text-[11px] font-medium text-neutral-500 hover:bg-neutral-100 hover:text-red-600 disabled:opacity-50 dark:text-neutral-400 dark:hover:bg-neutral-800 dark:hover:text-red-400"
                        >
                          Pass
                        </button>
                      </div>
                    ) : null}
                    {p.stage === "ic-review" && <IcStatus review={ic[p.id]} me={me} />}
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

function IcStatus({ review, me }: { review: IcReview | null | undefined; me: string | null }) {
  if (!review) return null;
  return (
    <p className="mt-1 text-[11px] text-neutral-500">
      IC review: {review.taskStatus.replace("_", " ")}
      {review.taskStatus === "open" && review.requestedBy === me && " — requested by you; another approver decides"}
    </p>
  );
}

const IC_BUTTON =
  "inline-flex h-6 cursor-pointer items-center rounded-[var(--rb-r-sm,6px)] px-2 text-[11px] font-medium disabled:opacity-50";

/**
 * The IC gate's next step for one card. No review (or a rejected/cancelled
 * one) → request it; an open review → approve/reject for an approver or admin
 * who did not request it; an approved review → record the investment.
 */
function IcActions({
  review,
  busy,
  canDecide,
  me,
  onRequest,
  onApprove,
  onReject,
  onInvest,
}: {
  review: IcReview | null | undefined;
  busy: boolean;
  canDecide: boolean;
  me: string | null;
  onRequest: () => void;
  onApprove: (taskId: string) => void;
  onReject: () => void;
  onInvest: () => void;
}) {
  if (review === undefined) return null;
  if (review === null || review.taskStatus === "rejected" || review.taskStatus === "cancelled") {
    return (
      <button
        type="button"
        disabled={busy}
        onClick={onRequest}
        className={cx(IC_BUTTON, "bg-neutral-100 text-neutral-700 hover:bg-neutral-200 dark:bg-neutral-800 dark:text-neutral-300 dark:hover:bg-neutral-700")}
      >
        Request IC approval
      </button>
    );
  }
  if (review.taskStatus === "approved") {
    return (
      <button
        type="button"
        disabled={busy}
        onClick={onInvest}
        className={cx(IC_BUTTON, "bg-emerald-600 text-white hover:bg-emerald-700")}
      >
        Mark invested
      </button>
    );
  }
  if (review.taskStatus === "open" && canDecide && review.requestedBy !== me) {
    return (
      <>
        <button
          type="button"
          disabled={busy}
          onClick={() => onApprove(review.taskId)}
          className={cx(IC_BUTTON, "bg-emerald-600 text-white hover:bg-emerald-700")}
        >
          Approve
        </button>
        <button
          type="button"
          disabled={busy}
          onClick={onReject}
          className={cx(IC_BUTTON, "text-neutral-500 hover:bg-neutral-100 hover:text-red-600 dark:text-neutral-400 dark:hover:bg-neutral-800")}
        >
          Reject
        </button>
      </>
    );
  }
  return null;
}
