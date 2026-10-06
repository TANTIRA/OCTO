"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { ArrowRight, Plus, RefreshCw } from "lucide-react";
import { cn } from "@/lib/utils";
import { ApiError, getJson, postJson } from "@/lib/api";
import { Button, IconButton, ring } from "@/components/ui/button";
import { StatusBadge } from "@/components/ui/badge";
import { Field, Input, SearchInput, Select, Segmented, Textarea } from "@/components/ui/controls";
import { Sheet } from "@/components/ui/overlay";
import { EmptyState, InlineAlert, Skeleton, useToast } from "@/components/feedback";
import { JourneyGuide, ago, failure, useLiveRole } from "./common";
import { MANUAL_SOURCES, OPEN_STAGES, STAGE_NAME, nextStep, type IcReview, type Prospect } from "./deals-api";
import { ProspectSheet } from "./prospect-sheet";

type View = "open" | "invested" | "passed";

/**
 * Deal pipeline (live). The board is a map of where every prospect stands;
 * the work happens in the deal sheet, where each stage offers exactly the
 * actions the server's state machine accepts. Moves are buttons, not drags:
 * every move is an audited event with an actor, and some need a reason.
 */
export function DealsBoard() {
  const { tenantId, canWrite } = useLiveRole();
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const openId = params.get("prospect");
  const toast = useToast();

  const [view, setView] = useState<View>("open");
  const [cols, setCols] = useState<Record<string, Prospect[]>>({});
  const [closed, setClosed] = useState<Prospect[]>([]);
  const [ic, setIc] = useState<Record<string, IcReview | null>>({});
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [query, setQuery] = useState("");
  const [adding, setAdding] = useState(false);

  const setOpen = useCallback(
    (id: string | null) => {
      const p = new URLSearchParams(params.toString());
      if (id) p.set("prospect", id);
      else p.delete("prospect");
      router.replace(`${pathname}${p.size ? `?${p}` : ""}`, { scroll: false });
    },
    [params, pathname, router],
  );

  const load = useCallback(async () => {
    if (!tenantId) return;
    setLoading(true);
    setError(null);
    try {
      if (view === "open") {
        const lists = await Promise.all(OPEN_STAGES.map(async (s) => [s.id, await getJson<Prospect[]>(`/api/v1/prospects?tenantId=${tenantId}&stage=${s.id}&limit=200`)] as const));
        setCols(Object.fromEntries(lists));
        const atIc = lists.find(([s]) => s === "ic-review")?.[1] ?? [];
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
      } else {
        setClosed(await getJson<Prospect[]>(`/api/v1/prospects?tenantId=${tenantId}&stage=${view}&limit=200`));
      }
    } catch (e) {
      setError(failure(e));
    } finally {
      setLoading(false);
    }
  }, [tenantId, view]);

  useEffect(() => {
    load();
  }, [load]);

  const match = useCallback((p: Prospect) => !query.trim() || `${p.name} ${p.sector ?? ""} ${p.region ?? ""}`.toLowerCase().includes(query.trim().toLowerCase()), [query]);
  const total = useMemo(() => OPEN_STAGES.reduce((n, s) => n + (cols[s.id]?.length ?? 0), 0), [cols]);
  const waiting = Object.values(ic).filter((r) => r?.taskStatus === "open").length;

  return (
    <div className="space-y-5">
      <JourneyGuide
        id="deals"
        steps={[
          { title: "Source", body: "Prospects arrive from the CRM sync, or you add one with its thesis. Every prospect starts in Sourced." },
          { title: "Screen", body: "Run the mandate's screening rules. A clear result can advance; an out-of-mandate one is passed automatically with the reasons." },
          { title: "Diligence", body: "Entering diligence opens an evidence checklist. Flag any missing workstream so someone gathers it." },
          { title: "IC decision", body: "An IC memo goes to an approver — never the person who asked. Only an approved review can be recorded as invested." },
        ]}
      />

      <div className="flex flex-wrap items-center gap-2">
        <Segmented
          label="Pipeline view"
          value={view}
          onChange={setView}
          items={[
            { value: "open", label: "Open pipeline" },
            { value: "invested", label: "Invested" },
            { value: "passed", label: "Passed" },
          ]}
        />
        <SearchInput value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Search name, sector, region…" aria-label="Search prospects" className="w-full sm:w-64" />
        <div className="ml-auto flex items-center gap-2">
          {view === "open" && !loading && (
            <span className="text-[12px] text-ink-3">
              <span className="tabular-nums">{total}</span> open{waiting > 0 && <> · <span className="tabular-nums">{waiting}</span> awaiting an approver</>}
            </span>
          )}
          <IconButton label="Refresh pipeline" icon={<RefreshCw className={cn(loading && "animate-spin motion-reduce:animate-none")} />} onClick={load} disabled={loading} />
          {canWrite && (
            <Button variant="primary" onClick={() => setAdding(true)}>
              <Plus /> Add prospect
            </Button>
          )}
        </div>
      </div>

      {!canWrite && <InlineAlert tone="restricted">You have view access in this workspace: you can follow every deal, but moves and approvals need a member or approver role.</InlineAlert>}
      {error && (
        <InlineAlert tone="danger" title="The pipeline didn’t load" action={<Button size="sm" onClick={load}>Retry</Button>}>
          {error}
        </InlineAlert>
      )}

      {view === "open" ? (
        <div className="grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-4">
          {OPEN_STAGES.map((stage) => {
            const rows = (cols[stage.id] ?? []).filter(match);
            return (
              <section key={stage.id} aria-label={`${stage.name}, ${rows.length} prospects`} className="flex min-w-0 flex-col rounded-lg border border-line bg-subtle">
                <header className="border-b border-line px-3 py-2.5">
                  <div className="flex items-center justify-between gap-2">
                    <h2 className="text-[13px] font-semibold text-ink">{stage.name}</h2>
                    <span className="text-[12px] tabular-nums text-ink-3">{loading ? "…" : rows.length}</span>
                  </div>
                  <p className="mt-0.5 text-[11px] leading-snug text-ink-3">{stage.does}</p>
                </header>
                <ul className="flex-1 space-y-2 p-2">
                  {loading && [0, 1].map((i) => <li key={i}><Skeleton className="h-20 rounded-md" /></li>)}
                  {!loading &&
                    rows.map((p) => (
                      <li key={p.id}>
                        <DealCard p={p} hint={nextStep(p, ic[p.id])} waiting={ic[p.id]?.taskStatus === "open"} onOpen={() => setOpen(p.id)} />
                      </li>
                    ))}
                  {!loading && rows.length === 0 && <li className="px-2 py-6 text-center text-[12px] text-ink-3">{query ? "No match in this stage" : "No prospects here"}</li>}
                </ul>
              </section>
            );
          })}
        </div>
      ) : (
        <section aria-label={`${STAGE_NAME[view]} prospects`} className="rounded-lg border border-line bg-surface">
          {loading ? (
            <div className="space-y-2 p-4">{[0, 1, 2].map((i) => <Skeleton key={i} className="h-12 rounded-md" />)}</div>
          ) : closed.filter(match).length === 0 ? (
            <EmptyState title={view === "invested" ? "No investments recorded yet" : "Nothing passed yet"} body={view === "invested" ? "A prospect lands here once an approved IC review is recorded as invested." : "Prospects passed by hand or screened out by the rules land here, each with its reason."} />
          ) : (
            <ul className="divide-y divide-line-subtle">
              {closed.filter(match).map((p) => (
                <li key={p.id}>
                  <button type="button" onClick={() => setOpen(p.id)} className={cn("flex w-full cursor-pointer items-center gap-3 px-4 py-3 text-left hover:bg-hover", ring)}>
                    <span className="min-w-0 flex-1">
                      <span className="block truncate text-[13px] font-medium text-ink">{p.name}</span>
                      <span className="block truncate text-[12px] text-ink-3">{[p.sector, p.region].filter(Boolean).join(" · ") || "Sector and region not recorded"}</span>
                    </span>
                    <span className="hidden text-[12px] text-ink-3 sm:inline">Decided by {p.decidedBy ?? "—"} · {ago(p.lastEventAt)}</span>
                    <ArrowRight aria-hidden className="size-4 text-ink-4" />
                  </button>
                </li>
              ))}
            </ul>
          )}
        </section>
      )}

      {openId && <ProspectSheet id={openId} onClose={() => setOpen(null)} onChanged={load} />}
      {adding && (
        <AddProspect
          tenantId={tenantId}
          onClose={() => setAdding(false)}
          onAdded={(p) => {
            setAdding(false);
            toast({ tone: "ok", title: `${p.name} added to Sourced`, body: "Open it to move it into screening." });
            if (view !== "open") setView("open");
            else load();
            setOpen(p.id);
          }}
        />
      )}
    </div>
  );
}

function DealCard({ p, hint, waiting, onOpen }: { p: Prospect; hint: string; waiting: boolean; onOpen: () => void }) {
  return (
    <button type="button" onClick={onOpen} className={cn("block w-full cursor-pointer rounded-md border border-line bg-surface p-3 text-left shadow-[0_1px_0_rgb(15_23_42/0.03)] transition-colors hover:border-line-strong", ring)}>
      <span className="block truncate text-[13px] font-semibold text-ink">{p.name}</span>
      <span className="mt-0.5 block truncate text-[12px] text-ink-3">{[p.sector, p.region].filter(Boolean).join(" · ") || "Sector and region not recorded"}</span>
      <span className="mt-2 flex items-center justify-between gap-2">
        {waiting ? <StatusBadge tone="warn">{hint}</StatusBadge> : <span className="truncate text-[11px] font-medium text-accent-ink">{hint}</span>}
        <span className="shrink-0 text-[11px] tabular-nums text-ink-4">{ago(p.lastEventAt)}</span>
      </span>
    </button>
  );
}

function AddProspect({ tenantId, onClose, onAdded }: { tenantId: string; onClose: () => void; onAdded: (p: Prospect) => void }) {
  const [name, setName] = useState("");
  const [source, setSource] = useState("manual");
  const [sector, setSector] = useState("");
  const [region, setRegion] = useState("");
  const [description, setDescription] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [touched, setTouched] = useState(false);
  const nameError = touched && !name.trim() ? "Give the prospect a name." : undefined;

  const submit = async () => {
    setTouched(true);
    if (!name.trim() || busy) return;
    setBusy(true);
    setError(null);
    try {
      onAdded(await postJson<Prospect>("/api/v1/prospects", { tenantId, name: name.trim(), source, sector: sector.trim() || null, region: region.trim() || null, description: description.trim() || null }));
    } catch (e) {
      setError(failure(e, { 400: "One of the fields is too long or invalid — names are capped at 300 characters, sector and region at 200." }));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Sheet
      open
      onClose={onClose}
      eyebrow="Deal pipeline"
      title="Add a prospect"
      footer={
        <div className="flex justify-end gap-2">
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" loading={busy} onClick={submit}>
            Add to Sourced
          </Button>
        </div>
      }
    >
      <form
        className="space-y-4"
        onSubmit={(e) => {
          e.preventDefault();
          submit();
        }}
      >
        <p className="text-[13px] text-ink-3">The prospect starts in Sourced. Sector, region and thesis are optional now, but the screening rules and the AI memos work from them — a prospect without a sector is sent to a person for review instead of being screened.</p>
        <Field id="p-name" label="Company or deal name" required error={nameError}>
          <Input id="p-name" value={name} onChange={(e) => setName(e.target.value)} onBlur={() => setTouched(true)} maxLength={300} aria-invalid={!!nameError} autoFocus />
        </Field>
        <Field id="p-source" label="Where it came from">
          <Select id="p-source" value={source} onChange={(e) => setSource(e.target.value)}>
            {MANUAL_SOURCES.map((s) => (
              <option key={s.value} value={s.value}>
                {s.label}
              </option>
            ))}
          </Select>
        </Field>
        <div className="grid grid-cols-2 gap-3">
          <Field id="p-sector" label="Sector" hint="e.g. Healthcare">
            <Input id="p-sector" value={sector} onChange={(e) => setSector(e.target.value)} maxLength={200} />
          </Field>
          <Field id="p-region" label="Region" hint="e.g. Indonesia">
            <Input id="p-region" value={region} onChange={(e) => setRegion(e.target.value)} maxLength={200} />
          </Field>
        </div>
        <Field id="p-thesis" label="Thesis" hint="One or two sentences on why this could fit the mandate.">
          <Textarea id="p-thesis" rows={4} value={description} onChange={(e) => setDescription(e.target.value)} maxLength={10_000} />
        </Field>
        {error && <InlineAlert tone="danger">{error}</InlineAlert>}
      </form>
    </Sheet>
  );
}
