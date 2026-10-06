"use client";

import { useState } from "react";
import { ClipboardPaste, Plus, RotateCcw, Trash2 } from "lucide-react";
import { postJson } from "@/lib/api";
import { Button, IconButton } from "@/components/ui/button";
import { StatusBadge } from "@/components/ui/badge";
import { Field, Input, Textarea } from "@/components/ui/controls";
import { InlineAlert } from "@/components/feedback";
import { CheckRow, JourneyGuide, Stat, failure, shortId, useLiveRole } from "./common";

type RecordRow = { externalId: string; amount: string; currency: string; date: string };
type Break = { kind: string; sourceSystem: string; sourceRef: string | null; ledgerEventId: string | null; detail: Record<string, string>; taskId: string; opened: boolean };
type Run = { runId: string; matched: number; breaks: Break[] };

const SOURCES = ["custodian", "fund-admin", "bank", "transfer-agent"];

/** Each break kind: what it means and how it is normally resolved. Fixes are ledger corrections — nothing is overwritten. */
const KIND: Record<string, { label: string; means: string; fix: string }> = {
  "missing-in-ibor": { label: "Missing in the IBOR", means: "The source has a record the ledger doesn’t.", fix: "Book the missing transaction, or confirm the source record is wrong." },
  "missing-in-source": { label: "Missing in the source", means: "The ledger has an event this complete source set doesn’t mention.", fix: "Ask the source for the record, or correct the ledger event with a reason." },
  "amount-mismatch": { label: "Amount differs", means: "Both sides have the record but the amounts differ beyond the tolerance.", fix: "Find the side that’s wrong; a ledger fix is a correction that points to the original." },
  "date-mismatch": { label: "Date differs", means: "The amounts agree but the dates are further apart than the tolerance.", fix: "Confirm the settlement date; correct whichever side is wrong." },
  "currency-mismatch": { label: "Currency differs", means: "The same reference is booked in a different currency.", fix: "Check the FX leg — this usually means a booking error." },
};

const emptyRow = (): RecordRow => ({ externalId: "", amount: "", currency: "USD", date: new Date().toISOString().slice(0, 10) });

/** Rows from a spreadsheet paste: id, amount, currency, date — comma, semicolon or tab separated, header optional. */
function parsePaste(text: string): RecordRow[] {
  return text
    .split(/\r?\n/)
    .map((l) => l.split(/\t|;|,(?=(?:[^"]*"[^"]*")*[^"]*$)/).map((c) => c.trim().replace(/^"|"$/g, "")))
    .filter((c) => c.length >= 4 && c[0] && Number.isFinite(Number(c[1].replace(/[, ]/g, ""))))
    .map(([externalId, amount, currency, date]) => ({ externalId, amount: amount.replace(/[, ]/g, ""), currency: currency.toUpperCase(), date }));
}

const rowError = (r: RecordRow): string | null => {
  if (!r.externalId.trim()) return "Reference is required";
  if (r.amount.trim() === "" || !Number.isFinite(Number(r.amount))) return "Amount must be a number";
  if (!/^[A-Z]{3}$/.test(r.currency)) return "Currency is a 3-letter code";
  if (!/^\d{4}-\d{2}-\d{2}$/.test(r.date)) return "Date is required";
  return null;
};

/**
 * Reconciliation (live). A source system's records are compared with the
 * ledger events OCTO holds for that system. Matches confirm the ledger; every
 * break opens a review task, and the fix is a correction on the ledger that
 * points to the original — the book is never edited in place.
 */
export function ReconciliationView() {
  const { tenantId, canWrite } = useLiveRole();
  const [source, setSource] = useState("custodian");
  const [rows, setRows] = useState<RecordRow[]>([emptyRow()]);
  const [paste, setPaste] = useState<string | null>(null);
  const [tolAmount, setTolAmount] = useState("0");
  const [tolDays, setTolDays] = useState("0");
  const [complete, setComplete] = useState(false);
  const [touched, setTouched] = useState(false);
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [run, setRun] = useState<{ result: Run; submitted: number; source: string; complete: boolean } | null>(null);

  const errors = rows.map(rowError);
  const dupes = rows.map((r, i) => r.externalId.trim() !== "" && rows.findIndex((x) => x.externalId.trim() === r.externalId.trim()) !== i);
  const tolValid = Number(tolAmount) >= 0 && Number.isInteger(Number(tolDays)) && Number(tolDays) >= 0;
  const valid = source.trim() !== "" && rows.length > 0 && errors.every((e) => !e) && !dupes.some(Boolean) && tolValid;

  const submit = async () => {
    setTouched(true);
    if (!valid || running) return;
    setRunning(true);
    setError(null);
    try {
      const result = await postJson<Run>("/api/v1/reconciliations", {
        tenantId,
        records: rows.map((r) => ({ sourceSystem: source.trim(), externalId: r.externalId.trim(), amount: r.amount, currency: r.currency, date: r.date })),
        tolerance: Number(tolAmount) > 0 || Number(tolDays) > 0 ? { amount: Number(tolAmount), days: Number(tolDays) } : undefined,
        complete,
      });
      setRun({ result, submitted: rows.length, source: source.trim(), complete });
    } catch (e) {
      setError(failure(e, { 400: "The batch couldn’t be read — check for duplicate references, amounts and 3-letter currency codes.", 409: "The ledger holds two current events for one of these references. It needs a correction before this batch can be matched." }));
    } finally {
      setRunning(false);
    }
  };

  const grouped = run ? Object.entries(Object.groupBy(run.result.breaks, (b) => b.kind)) : [];

  if (!canWrite) {
    return (
      <div className="space-y-5">
        <Guide />
        <InlineAlert tone="restricted">A reconciliation run writes review tasks, so it needs a member or approver role in this workspace.</InlineAlert>
      </div>
    );
  }

  return (
    <div className="space-y-5">
      <Guide />

      {!run ? (
        <form
          className="space-y-5 rounded-lg border border-line bg-surface p-4"
          onSubmit={(e) => {
            e.preventDefault();
            submit();
          }}
        >
          <div>
            <h2 className="text-card font-semibold text-ink">1 · Which source are you checking?</h2>
            <p className="text-[12px] text-ink-3">OCTO compares these records with the ledger events booked from the same system.</p>
            <div className="mt-2 flex flex-wrap items-center gap-2">
              {SOURCES.map((s) => (
                <Button key={s} size="sm" variant={source === s ? "primary" : "secondary"} aria-pressed={source === s} onClick={() => setSource(s)}>
                  {s}
                </Button>
              ))}
              <Input aria-label="Other source system" maxLength={200} placeholder="Other system…" value={SOURCES.includes(source) ? "" : source} onChange={(e) => setSource(e.target.value)} className="w-44" />
            </div>
          </div>

          <div>
            <div className="flex flex-wrap items-end justify-between gap-2">
              <div>
                <h2 className="text-card font-semibold text-ink">2 · Records from {source || "the source"}</h2>
                <p className="text-[12px] text-ink-3">One row per transaction: its reference in the source, amount, currency and date.</p>
              </div>
              <Button size="sm" onClick={() => setPaste(paste === null ? "" : null)}>
                <ClipboardPaste /> {paste === null ? "Paste from a spreadsheet" : "Close paste"}
              </Button>
            </div>
            {paste !== null && (
              <div className="mt-3 space-y-2 rounded-md border border-line bg-subtle p-3">
                <Field id="paste" label="Paste rows" hint="Columns in order: reference, amount, currency, date (YYYY-MM-DD). Comma, semicolon or tab separated; a header row is skipped.">
                  <Textarea id="paste" rows={5} value={paste} onChange={(e) => setPaste(e.target.value)} placeholder={"TXN-1001, 1250000.00, USD, 2026-09-29\nTXN-1002, 84000.00, USD, 2026-09-29"} className="font-data" />
                </Field>
                <div className="flex items-center gap-2">
                  <Button
                    size="sm"
                    variant="primary"
                    disabled={parsePaste(paste).length === 0}
                    onClick={() => {
                      const parsed = parsePaste(paste);
                      setRows((r) => [...r.filter((x) => x.externalId || x.amount), ...parsed]);
                      setPaste(null);
                    }}
                  >
                    Add {parsePaste(paste).length} rows
                  </Button>
                  <span className="text-[12px] text-ink-3">{paste.trim() && parsePaste(paste).length === 0 ? "No readable rows yet." : ""}</span>
                </div>
              </div>
            )}
            <div className="mt-3 overflow-x-auto">
              <table className="w-full min-w-[560px] text-[13px]">
                <thead>
                  <tr className="text-left text-[12px] text-ink-3">
                    <th className="pb-1 font-medium">Reference</th>
                    <th className="pb-1 font-medium">Amount</th>
                    <th className="pb-1 font-medium">Currency</th>
                    <th className="pb-1 font-medium">Date</th>
                    <th className="sr-only">Remove</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((r, i) => {
                    const err = touched ? errors[i] ?? (dupes[i] ? "Reference appears twice" : null) : null;
                    const set = (patch: Partial<RecordRow>) => setRows(rows.map((x, j) => (j === i ? { ...x, ...patch } : x)));
                    return (
                      <tr key={i} className="align-top">
                        <td className="py-1 pr-2">
                          <Input aria-label={`Reference ${i + 1}`} maxLength={200} value={r.externalId} onChange={(e) => set({ externalId: e.target.value })} placeholder="TXN-1001" aria-invalid={!!err} />
                          {err && <p className="mt-0.5 text-[11px] text-danger">{err}</p>}
                        </td>
                        <td className="py-1 pr-2">
                          <Input aria-label={`Amount ${i + 1}`} type="number" step="any" value={r.amount} onChange={(e) => set({ amount: e.target.value })} placeholder="0.00" />
                        </td>
                        <td className="w-24 py-1 pr-2">
                          <Input aria-label={`Currency ${i + 1}`} value={r.currency} onChange={(e) => set({ currency: e.target.value.toUpperCase() })} maxLength={3} />
                        </td>
                        <td className="w-40 py-1 pr-2">
                          <Input aria-label={`Date ${i + 1}`} type="date" value={r.date} onChange={(e) => set({ date: e.target.value })} />
                        </td>
                        <td className="w-10 py-1">{rows.length > 1 && <IconButton size="sm" variant="ghost" label={`Remove row ${i + 1}`} icon={<Trash2 />} onClick={() => setRows(rows.filter((_, j) => j !== i))} />}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
            <Button size="xs" variant="ghost" className="mt-1" onClick={() => setRows([...rows, emptyRow()])}>
              <Plus /> Add row
            </Button>
          </div>

          <div>
            <h2 className="text-card font-semibold text-ink">3 · How strict?</h2>
            <div className="mt-2 grid gap-3 sm:grid-cols-[9rem_9rem_1fr]">
              <Field id="tol-amt" label="Amount tolerance" hint="0 = exact">
                <Input id="tol-amt" type="number" min="0" step="any" value={tolAmount} onChange={(e) => setTolAmount(e.target.value)} />
              </Field>
              <Field id="tol-days" label="Date tolerance (days)" hint="0 = same day">
                <Input id="tol-days" type="number" min="0" step="1" value={tolDays} onChange={(e) => setTolDays(e.target.value)} />
              </Field>
              <div className="pt-5">
                <CheckRow checked={complete} onChange={setComplete} label="This is the source’s complete record set" hint="Tick only for a full extract: ledger events the batch doesn’t mention are then reported as missing in the source." />
              </div>
            </div>
            {!tolValid && <p className="mt-1 text-[12px] text-danger">Tolerances must be zero or more; days are whole numbers.</p>}
          </div>

          {error && <InlineAlert tone="danger" title="The run didn’t start">{error}</InlineAlert>}
          <div className="flex items-center justify-end gap-3 border-t border-line pt-4">
            <span className="text-[12px] text-ink-3">{rows.length} record{rows.length === 1 ? "" : "s"} from {source || "—"}</span>
            <Button type="submit" variant="primary" loading={running}>
              Run reconciliation
            </Button>
          </div>
        </form>
      ) : (
        <section aria-label="Reconciliation result" className="space-y-4 rounded-lg border border-line bg-surface p-4">
          <div className="flex flex-wrap items-end justify-between gap-3">
            <div>
              <h2 className="text-card font-semibold text-ink">Result · {run.source}</h2>
              <p className="text-[12px] text-ink-3">
                Run {shortId(run.result.runId)} · {run.complete ? "complete record set" : "partial batch — ledger events missing from the source are not checked"}
              </p>
            </div>
            <Button onClick={() => setRun(null)}>
              <RotateCcw /> Start another run
            </Button>
          </div>
          <div className="grid grid-cols-3 gap-3">
            <Stat label="Records submitted" value={run.submitted} />
            <Stat label="Matched the ledger" value={run.result.matched} tone="ok" />
            <Stat label="Breaks" value={run.result.breaks.length} tone={run.result.breaks.length ? "danger" : undefined} />
          </div>
          {run.result.breaks.length === 0 ? (
            <InlineAlert tone="ok" title="Everything matched">The ledger agrees with {run.source} for every record submitted. Nothing needs review.</InlineAlert>
          ) : (
            <>
              <InlineAlert tone="info">Each break opened a review task (or joined the one already open for the same record). Resolve it with a ledger correction that cites the original — the book is never edited in place.</InlineAlert>
              {grouped.map(([kind, list]) => (
                <div key={kind} className="rounded-md border border-line">
                  <div className="border-b border-line bg-subtle px-3 py-2">
                    <div className="flex items-center gap-2">
                      <StatusBadge tone="danger">{KIND[kind]?.label ?? kind}</StatusBadge>
                      <span className="text-[12px] tabular-nums text-ink-3">{list!.length}</span>
                    </div>
                    <p className="mt-1 text-[12px] text-ink-2">
                      {KIND[kind]?.means} <span className="text-ink-3">Usual fix: {KIND[kind]?.fix}</span>
                    </p>
                  </div>
                  <ul className="divide-y divide-line-subtle">
                    {list!.map((b) => (
                      <li key={`${b.kind}-${b.sourceRef}`} className="flex flex-wrap items-center gap-x-4 gap-y-1 px-3 py-2 text-[13px]">
                        <span className="font-data text-ink">{b.sourceRef ?? "—"}</span>
                        <span className="min-w-0 flex-1 text-ink-2">
                          {Object.entries(b.detail)
                            .map(([k, v]) => `${k}: ${v}`)
                            .join(" · ")}
                        </span>
                        <span className="text-[12px] text-ink-3">
                          Task {shortId(b.taskId)} · {b.opened ? "opened" : "already open"}
                        </span>
                      </li>
                    ))}
                  </ul>
                </div>
              ))}
            </>
          )}
        </section>
      )}
    </div>
  );
}

function Guide() {
  return (
    <JourneyGuide
      id="reconciliation"
      steps={[
        { title: "Pick the source", body: "A custodian, fund administrator or bank statement — whichever system you are checking the book against." },
        { title: "Bring its records", body: "Type them, or paste rows straight from the source’s export. Each needs its reference, amount, currency and date." },
        { title: "Compare with the IBOR", body: "OCTO matches each record to the ledger events booked from that system, within the tolerance you set." },
        { title: "Resolve the breaks", body: "Matches confirm the book. Every break opens a review task; the fix is a ledger correction with a reason." },
      ]}
    />
  );
}
