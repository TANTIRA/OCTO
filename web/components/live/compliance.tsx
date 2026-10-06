"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { Plus, RefreshCw, Sparkles, Trash2 } from "lucide-react";
import { cn } from "@/lib/utils";
import { getJson, postJson } from "@/lib/api";
import { Button, IconButton, LinkButton } from "@/components/ui/button";
import { StatusBadge, Tag } from "@/components/ui/badge";
import { Field, Input, Select } from "@/components/ui/controls";
import { ConfirmDialog, Sheet } from "@/components/ui/overlay";
import { EmptyState, InlineAlert, Skeleton, useToast } from "@/components/feedback";
import { JourneyGuide, Stat, failure, shortId, useLiveRole } from "./common";

type Check = { type: string; maxFraction?: number | string | null; currency?: string | null; minRatio?: number | string | null };
type Rule = { ruleId: string; version: number; name: string; check: Check };
type Outcome = { ruleId: string; version: number; result: "pass" | "breach" | "not-evaluable" | string; measured: Record<string, string>; explanation: string; taskId: string | null; recorded: boolean };
type Rationale = { status: "completed" | "refused"; rationale?: string; note?: string | null };
type Row = { key: string; value: string };

const TYPES: Record<string, { label: string; needs: string }> = {
  "concentration-limit": { label: "Concentration limit", needs: "Exposure by asset" },
  "currency-exposure-limit": { label: "Currency exposure limit", needs: "Exposure by currency" },
  "coverage-floor": { label: "Coverage floor", needs: "Coverage ratio" },
};

const pctOf = (v: unknown) => `${Math.round(Number(v) * 1000) / 10}%`;

/** The rule as a sentence a portfolio manager would say. */
function describe(c: Check): string {
  if (c.type === "concentration-limit") return `No single asset may exceed ${pctOf(c.maxFraction)} of gross exposure.`;
  if (c.type === "currency-exposure-limit") return `${c.currency} may be at most ${pctOf(c.maxFraction)} of currency exposure.`;
  if (c.type === "coverage-floor") return `Coverage must stay at or above ${Number(c.minRatio)}× under the stress scenario.`;
  return c.type;
}

const RESULT: Record<string, { label: string; tone: "ok" | "danger" | "neutral"; means: string }> = {
  pass: { label: "Pass", tone: "ok", means: "Inside the limit." },
  breach: { label: "Breach", tone: "danger", means: "Outside the limit — a review task was opened for a person to resolve." },
  "not-evaluable": { label: "Not evaluable", tone: "neutral", means: "The figures this rule needs weren’t supplied, so it wasn’t judged." },
};

/**
 * Compliance (live). Rules state the limits; a check scores one portfolio's
 * figures against every active rule as of a date; each breach opens a review
 * task, and the AI can draft the narrative for the file — judged like every
 * other AI output. The form only asks for the figures the active rules use.
 */
export function ComplianceView() {
  const { tenantId, canWrite, canGovern } = useLiveRole();
  const toast = useToast();
  const [rules, setRules] = useState<Rule[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [subject, setSubject] = useState("");
  const [asOf, setAsOf] = useState(() => new Date().toISOString().slice(0, 10));
  const [ccyRows, setCcyRows] = useState<Row[]>([{ key: "", value: "" }]);
  const [assetCcy, setAssetCcy] = useState("USD");
  const [assetRows, setAssetRows] = useState<Row[]>([{ key: "", value: "" }]);
  const [coverage, setCoverage] = useState({ currency: "USD", scenario: "", ratio: "" });
  const [touched, setTouched] = useState(false);
  const [running, setRunning] = useState(false);
  const [outcomes, setOutcomes] = useState<Outcome[] | null>(null);
  const [checked, setChecked] = useState<{ subject: string; asOf: string } | null>(null);
  const [rationale, setRationale] = useState<Rationale | null>(null);
  const [narrating, setNarrating] = useState(false);
  const [runError, setRunError] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);
  const [retiring, setRetiring] = useState<Rule | null>(null);

  const load = useCallback(async () => {
    if (!tenantId) return;
    setLoading(true);
    setError(null);
    try {
      setRules(await getJson<Rule[]>(`/api/v1/compliance/rules?tenantId=${tenantId}`));
    } catch (e) {
      setError(failure(e));
    } finally {
      setLoading(false);
    }
  }, [tenantId]);

  useEffect(() => {
    load();
  }, [load]);

  const has = (t: string) => rules.some((r) => r.check.type === t);
  const filled = (rows: Row[]) => rows.filter((r) => r.key.trim() || r.value.trim());
  const rowsValid = (rows: Row[], keyRe: RegExp) => filled(rows).every((r) => keyRe.test(r.key.trim()) && r.value.trim() !== "" && Number.isFinite(Number(r.value)) && Number(r.value) >= 0);
  const ccyValid = rowsValid(ccyRows, /^[A-Z]{3}$/);
  const assetValid = rowsValid(assetRows, /^.{1,120}$/) && (filled(assetRows).length === 0 || /^[A-Z]{3}$/.test(assetCcy));
  const coverageValid = coverage.ratio.trim() === "" || (Number.isFinite(Number(coverage.ratio)) && /^[A-Z]{3}$/.test(coverage.currency) && coverage.scenario.trim() !== "");
  const subjectError = touched && !subject.trim() ? "Name the fund or portfolio being checked." : undefined;
  const formValid = subject.trim() !== "" && asOf !== "" && ccyValid && assetValid && coverageValid;

  const body = () => ({
    tenantId,
    subject: subject.trim(),
    asOf,
    currencyExposure: filled(ccyRows).length ? Object.fromEntries(filled(ccyRows).map((r) => [r.key.trim(), Number(r.value)])) : undefined,
    exposure: filled(assetRows).length ? { currency: assetCcy, byAsset: Object.fromEntries(filled(assetRows).map((r) => [r.key.trim(), Number(r.value)])) } : undefined,
    coverage: coverage.ratio.trim() ? { currency: coverage.currency, scenario: coverage.scenario.trim(), ratio: Number(coverage.ratio) } : undefined,
  });

  const unsupplied = useMemo(
    () =>
      rules.filter((r) => (r.check.type === "currency-exposure-limit" && !filled(ccyRows).length) || (r.check.type === "concentration-limit" && !filled(assetRows).length) || (r.check.type === "coverage-floor" && !coverage.ratio.trim())).length,
    [rules, ccyRows, assetRows, coverage.ratio],
  );

  /** The outcome renders below the fold; bring it into view (and focus) once it lands. */
  const revealResults = () =>
    window.requestAnimationFrame(() => {
      const el = document.getElementById("compliance-results");
      el?.scrollIntoView({ block: "start", behavior: window.matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth" });
      el?.focus({ preventScroll: true });
    });

  const run = async () => {
    setTouched(true);
    if (!formValid || running) return;
    setRunning(true);
    setRunError(null);
    setRationale(null);
    try {
      setOutcomes(await postJson<Outcome[]>("/api/v1/compliance/evaluations", body()));
      setChecked({ subject: subject.trim(), asOf });
      revealResults();
    } catch (e) {
      setRunError(failure(e, { 400: "A figure couldn’t be read — check currency codes (3 letters, e.g. USD) and that amounts are numbers." }));
    } finally {
      setRunning(false);
    }
  };

  const narrate = async () => {
    setNarrating(true);
    setRunError(null);
    try {
      setRationale(await postJson<Rationale>("/api/v1/compliance/rationale", body()));
    } catch (e) {
      setRunError(failure(e));
    } finally {
      setNarrating(false);
    }
  };

  const counts = outcomes ? { pass: outcomes.filter((o) => o.result === "pass").length, breach: outcomes.filter((o) => o.result === "breach").length, ne: outcomes.filter((o) => o.result === "not-evaluable").length } : null;

  return (
    <div className="space-y-5">
      <JourneyGuide
        id="compliance"
        steps={[
          { title: "Rules set the limits", body: "Each rule is a versioned limit — concentration, currency exposure or a coverage floor. Approvers maintain them." },
          { title: "Enter the figures", body: "Name the fund and date, then give only the figures the rules need. A rule with no figures is reported as not evaluable." },
          { title: "Read the outcome", body: "Every rule returns pass, breach or not evaluable, with the measured value against its limit. The check is recorded." },
          { title: "Breaches become tasks", body: "Each breach opens a review task. Draft the AI rationale for the file — it is judged before it is kept." },
        ]}
      />

      {error && (
        <InlineAlert tone="danger" title="Rules didn’t load" action={<Button size="sm" onClick={load}>Retry</Button>}>
          {error}
        </InlineAlert>
      )}

      <div className="grid grid-cols-1 gap-4 xl:grid-cols-12">
        <section aria-label="Active rules" className="rounded-lg border border-line bg-surface xl:col-span-5">
          <header className="flex items-center justify-between gap-2 border-b border-line px-4 py-3">
            <div>
              <h2 className="text-card font-semibold text-ink">1 · Active rules</h2>
              <p className="text-[12px] text-ink-3">What every check is scored against.</p>
            </div>
            <div className="flex items-center gap-1.5">
              <IconButton size="sm" label="Reload rules" icon={<RefreshCw className={cn(loading && "animate-spin motion-reduce:animate-none")} />} onClick={load} />
              {canGovern && (
                <Button size="sm" onClick={() => setAdding(true)}>
                  <Plus /> Add rule
                </Button>
              )}
            </div>
          </header>
          {loading ? (
            <div className="space-y-2 p-4">{[0, 1, 2].map((i) => <Skeleton key={i} className="h-14 rounded-md" />)}</div>
          ) : rules.length === 0 ? (
            <EmptyState title="No active rules" body={canGovern ? "Add the first limit — every check is scored against the active rules." : "An approver needs to add the limits before checks mean anything."} />
          ) : (
            <ul className="divide-y divide-line-subtle">
              {rules.map((r) => (
                <li key={r.ruleId} className="flex items-start gap-3 px-4 py-3">
                  <div className="min-w-0 flex-1">
                    <p className="text-[13px] font-medium text-ink">{r.name}</p>
                    <p className="mt-0.5 text-[12px] text-ink-2">{describe(r.check)}</p>
                    <p className="mt-1 flex flex-wrap items-center gap-1.5 text-[11px] text-ink-3">
                      <Tag>{TYPES[r.check.type]?.label ?? r.check.type}</Tag>
                      {r.ruleId} · v{r.version}
                    </p>
                  </div>
                  {canGovern && <IconButton size="sm" variant="ghost" label={`Retire ${r.name}`} icon={<Trash2 />} onClick={() => setRetiring(r)} />}
                </li>
              ))}
            </ul>
          )}
        </section>

        <section aria-label="Run a compliance check" className="rounded-lg border border-line bg-surface xl:col-span-7">
          <header className="border-b border-line px-4 py-3">
            <h2 className="text-card font-semibold text-ink">2 · Run a check</h2>
            <p className="text-[12px] text-ink-3">Figures for one fund or portfolio, as of one date.</p>
          </header>
          {!canWrite ? (
            <div className="p-4">
              <InlineAlert tone="restricted">Running a check records it on the audit trail, so it needs a member or approver role.</InlineAlert>
            </div>
          ) : (
            <form
              className="space-y-5 p-4"
              onSubmit={(e) => {
                e.preventDefault();
                run();
              }}
            >
              <div className="grid gap-3 sm:grid-cols-[1fr_11rem]">
                <Field id="c-subject" label="Fund or portfolio" required error={subjectError} hint="As it should appear on the record, e.g. flagship-ii">
                  <Input id="c-subject" value={subject} onChange={(e) => setSubject(e.target.value)} onBlur={() => setTouched(true)} maxLength={300} aria-invalid={!!subjectError} />
                </Field>
                <Field id="c-asof" label="As of" required>
                  <Input id="c-asof" type="date" value={asOf} onChange={(e) => setAsOf(e.target.value)} />
                </Field>
              </div>

              {has("currency-exposure-limit") && (
                <Pairs title="Exposure by currency" hint="Amounts or shares — OCTO uses each currency’s fraction of the total." keyLabel="Currency" keyPlaceholder="IDR" valueLabel="Exposure" rows={ccyRows} setRows={setCcyRows} upper invalid={!ccyValid} invalidText="Use 3-letter currency codes and non-negative numbers." />
              )}
              {has("concentration-limit") && (
                <div className="space-y-2">
                  <Pairs title="Exposure by asset" hint="Gross exposure per asset; the largest one is compared with the limit." keyLabel="Asset" keyPlaceholder="Kirana Consumer" valueLabel="Exposure" rows={assetRows} setRows={setAssetRows} invalid={!assetValid} invalidText="Name each asset and give a non-negative number; the currency must be a 3-letter code." />
                  <Field id="c-asset-ccy" label="Currency of these amounts">
                    <Input id="c-asset-ccy" value={assetCcy} onChange={(e) => setAssetCcy(e.target.value.toUpperCase())} maxLength={3} className="w-24" />
                  </Field>
                </div>
              )}
              {has("coverage-floor") && (
                <fieldset className="space-y-2">
                  <legend className="text-[12px] font-medium text-ink-2">Coverage under stress</legend>
                  <p className="text-[12px] text-ink-3">The liquidity coverage ratio from your stress test.</p>
                  <div className="grid gap-2 sm:grid-cols-3">
                    <Input aria-label="Coverage currency" value={coverage.currency} onChange={(e) => setCoverage({ ...coverage, currency: e.target.value.toUpperCase() })} maxLength={3} placeholder="USD" />
                    <Input aria-label="Stress scenario" maxLength={200} value={coverage.scenario} onChange={(e) => setCoverage({ ...coverage, scenario: e.target.value })} placeholder="Scenario, e.g. rates +200bp" />
                    <Input aria-label="Coverage ratio" type="number" step="0.01" min="0" value={coverage.ratio} onChange={(e) => setCoverage({ ...coverage, ratio: e.target.value })} placeholder="Ratio, e.g. 1.35" />
                  </div>
                  {!coverageValid && <p className="text-[12px] text-danger">With a ratio, give a 3-letter currency and name the scenario.</p>}
                </fieldset>
              )}

              {rules.length > 0 && unsupplied > 0 && <p className="text-[12px] text-ink-3">{unsupplied} of {rules.length} rules have no figures yet and will be reported as not evaluable.</p>}
              {runError && <InlineAlert tone="danger">{runError}</InlineAlert>}
              <div className="flex justify-end">
                <Button type="submit" variant="primary" loading={running} disabled={rules.length === 0}>
                  Run check against {rules.length} rule{rules.length === 1 ? "" : "s"}
                </Button>
              </div>
            </form>
          )}
        </section>
      </div>

      {outcomes && counts && checked && (
        <section id="compliance-results" tabIndex={-1} aria-label="Check results" className="scroll-mt-4 space-y-4 rounded-lg border border-line bg-surface p-4 focus:outline-none">
          <div className="flex flex-wrap items-end justify-between gap-3">
            <div>
              <h2 className="text-card font-semibold text-ink">3 · Outcome for {checked.subject}</h2>
              <p className="text-[12px] text-ink-3">As of {checked.asOf} · recorded on the audit trail</p>
            </div>
            <Button loading={narrating} onClick={narrate}>
              <Sparkles /> Draft AI rationale
            </Button>
          </div>
          <div className="grid grid-cols-3 gap-3">
            <Stat label="Pass" value={counts.pass} tone="ok" />
            <Stat label="Breach" value={counts.breach} tone={counts.breach ? "danger" : undefined} />
            <Stat label="Not evaluable" value={counts.ne} />
          </div>
          <ul className="space-y-2">
            {outcomes.map((o) => {
              const rule = rules.find((r) => r.ruleId === o.ruleId);
              const res = RESULT[o.result] ?? { label: o.result, tone: "neutral" as const, means: "" };
              return (
                <li key={o.ruleId} className="rounded-md border border-line p-3">
                  <div className="flex flex-wrap items-center gap-2">
                    <StatusBadge tone={res.tone}>{res.label}</StatusBadge>
                    <span className="text-[13px] font-medium text-ink">{rule?.name ?? o.ruleId}</span>
                    <span className="text-[11px] text-ink-3">v{o.version}</span>
                  </div>
                  <p className="mt-1 text-[13px] text-ink-2">{o.explanation.charAt(0).toUpperCase() + o.explanation.slice(1)}.</p>
                  <Gauge o={o} />
                  <p className="mt-1 text-[12px] text-ink-3">
                    {res.means}
                    {o.taskId && <> Task {shortId(o.taskId)}.</>}
                  </p>
                </li>
              );
            })}
          </ul>
          {rationale && (
            <div className="space-y-2 rounded-md border border-line bg-subtle p-3">
              <div className="flex items-center gap-2">
                <span className="text-[13px] font-semibold text-ink">AI rationale</span>
                <StatusBadge tone={rationale.status === "completed" ? "ok" : "warn"}>{rationale.status === "completed" ? "Passed the judge" : "Refused by the judge"}</StatusBadge>
              </div>
              <p className="text-[13px] leading-relaxed text-ink-2">{rationale.rationale ?? rationale.note ?? "No rationale was released."}</p>
              <LinkButton size="sm" variant="link" href="/app/agents?workflow=compliance-rationale">
                See it in Agent runs
              </LinkButton>
            </div>
          )}
        </section>
      )}

      {adding && (
        <AddRule
          tenantId={tenantId}
          onClose={() => setAdding(false)}
          onAdded={(r) => {
            setAdding(false);
            toast({ tone: "ok", title: `Rule “${r.name}” is active`, body: `Version ${r.version}. Every new check is scored against it.` });
            load();
          }}
        />
      )}
      <ConfirmDialog
        open={!!retiring}
        title="Retire this rule?"
        body={<>“{retiring?.name}” leaves the active set, so new checks stop scoring it. Its history stays on record and past outcomes still reference it.</>}
        confirmLabel="Retire rule"
        danger
        onCancel={() => setRetiring(null)}
        onConfirm={async () => {
          const r = retiring;
          setRetiring(null);
          if (!r) return;
          try {
            await postJson(`/api/v1/compliance/rules/${r.ruleId}/retire`, { tenantId });
            toast({ tone: "ok", title: `“${r.name}” retired` });
            load();
          } catch (e) {
            toast({ tone: "danger", title: "Couldn’t retire the rule", body: failure(e, { 404: "Only approvers and admins can retire rules." }) });
          }
        }}
      />
    </div>
  );
}

/** Measured value against its limit, as a bar — so "38% vs 40%" reads at a glance. */
function Gauge({ o }: { o: Outcome }) {
  const m = o.measured;
  const value = Number(m.fraction ?? m.ratio);
  const limit = Number(m.limit ?? m.floor);
  if (!Number.isFinite(value) || !Number.isFinite(limit)) return null;
  const isRatio = m.ratio !== undefined;
  const max = Math.max(value, limit) * 1.25 || 1;
  const show = (v: number) => (isRatio ? `${v.toFixed(2)}×` : `${(v * 100).toFixed(1)}%`);
  return (
    <div className="mt-2">
      <div className="relative h-2 rounded-full bg-sunken" role="img" aria-label={`Measured ${show(value)} against a ${isRatio ? "floor" : "limit"} of ${show(limit)}`}>
        <div className={cn("h-full rounded-full", o.result === "breach" ? "bg-mark-danger" : "bg-mark-ok")} style={{ width: `${(value / max) * 100}%` }} />
        <span aria-hidden className="absolute -top-1 h-4 w-0.5 bg-ink" style={{ left: `${(limit / max) * 100}%` }} />
      </div>
      <p className="mt-1 text-[11px] tabular-nums text-ink-3">
        Measured {show(value)} · {isRatio ? "floor" : "limit"} {show(limit)}
        {m.asset && <> · largest asset {m.asset}</>}
      </p>
    </div>
  );
}

function Pairs({ title, hint, keyLabel, keyPlaceholder, valueLabel, rows, setRows, upper, invalid, invalidText }: { title: string; hint: string; keyLabel: string; keyPlaceholder: string; valueLabel: string; rows: Row[]; setRows: (r: Row[]) => void; upper?: boolean; invalid: boolean; invalidText: string }) {
  return (
    <fieldset className="space-y-2">
      <legend className="text-[12px] font-medium text-ink-2">{title}</legend>
      <p className="text-[12px] text-ink-3">{hint}</p>
      {rows.map((r, i) => (
        <div key={i} className="flex items-center gap-2">
          <Input aria-label={`${title}: ${keyLabel.toLowerCase()} ${i + 1}`} value={r.key} placeholder={keyPlaceholder} onChange={(e) => setRows(rows.map((x, j) => (j === i ? { ...x, key: upper ? e.target.value.toUpperCase() : e.target.value } : x)))} maxLength={upper ? 3 : 120} className={upper ? "w-24" : "flex-1"} />
          <Input aria-label={`${title}: ${valueLabel.toLowerCase()} ${i + 1}`} type="number" min="0" step="any" value={r.value} placeholder={valueLabel} onChange={(e) => setRows(rows.map((x, j) => (j === i ? { ...x, value: e.target.value } : x)))} className="w-40" />
          {rows.length > 1 && <IconButton size="sm" variant="ghost" label={`Remove ${keyLabel.toLowerCase()} ${i + 1}`} icon={<Trash2 />} onClick={() => setRows(rows.filter((_, j) => j !== i))} />}
        </div>
      ))}
      <Button size="xs" variant="ghost" onClick={() => setRows([...rows, { key: "", value: "" }])}>
        <Plus /> Add {keyLabel.toLowerCase()}
      </Button>
      {invalid && <p className="text-[12px] text-danger">{invalidText}</p>}
    </fieldset>
  );
}

function AddRule({ tenantId, onClose, onAdded }: { tenantId: string; onClose: () => void; onAdded: (r: Rule) => void }) {
  const [name, setName] = useState("");
  const [type, setType] = useState("concentration-limit");
  const [limit, setLimit] = useState("");
  const [currency, setCurrency] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const ruleId = name.trim().toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "").slice(0, 64);
  const isRatio = type === "coverage-floor";
  const n = Number(limit);
  const limitOk = limit.trim() !== "" && Number.isFinite(n) && n > 0 && (isRatio || n <= 100);
  const valid = name.trim() !== "" && /^[a-z0-9]/.test(ruleId) && limitOk && (type !== "currency-exposure-limit" || /^[A-Z]{3}$/.test(currency));

  const submit = async () => {
    if (!valid || busy) return;
    setBusy(true);
    setError(null);
    const check = isRatio ? { type, minRatio: n } : { type, maxFraction: n / 100, ...(type === "currency-exposure-limit" ? { currency } : {}) };
    try {
      onAdded(await postJson<Rule>("/api/v1/compliance/rules", { tenantId, ruleId, name: name.trim(), check }));
    } catch (e) {
      setError(failure(e, { 404: "Only approvers and admins can define rules.", 400: "The rule couldn’t be read — check the limit and currency." }));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Sheet open onClose={onClose} eyebrow="Compliance" title="Add a rule" footer={<div className="flex justify-end gap-2"><Button onClick={onClose}>Cancel</Button><Button variant="primary" loading={busy} disabled={!valid} onClick={submit}>Activate rule</Button></div>}>
      <div className="space-y-4">
        <p className="text-[13px] text-ink-3">Rules are versioned: saving one with the same name adds a new version, and past checks keep the version they were scored against.</p>
        <Field id="r-name" label="Rule name" required hint={ruleId ? `Rule id: ${ruleId}` : "e.g. No single asset above 20%"}>
          <Input id="r-name" value={name} onChange={(e) => setName(e.target.value)} maxLength={200} autoFocus />
        </Field>
        <Field id="r-type" label="Kind of limit">
          <Select id="r-type" value={type} onChange={(e) => setType(e.target.value)}>
            {Object.entries(TYPES).map(([k, v]) => (
              <option key={k} value={k}>
                {v.label}
              </option>
            ))}
          </Select>
        </Field>
        {type === "currency-exposure-limit" && (
          <Field id="r-ccy" label="Currency" required hint="3-letter code, e.g. IDR">
            <Input id="r-ccy" value={currency} onChange={(e) => setCurrency(e.target.value.toUpperCase())} maxLength={3} className="w-24" />
          </Field>
        )}
        <Field id="r-limit" label={isRatio ? "Minimum coverage ratio (×)" : "Maximum share (%)"} required hint={isRatio ? "e.g. 1.2 means coverage must be at least 1.2×" : "e.g. 20 means at most 20%"}>
          <Input id="r-limit" type="number" min="0" step="any" value={limit} onChange={(e) => setLimit(e.target.value)} className="w-32" />
        </Field>
        {limitOk && <p className="rounded-md bg-subtle px-3 py-2 text-[13px] text-ink-2">{describe(isRatio ? { type, minRatio: n } : { type, maxFraction: n / 100, currency })}</p>}
        {error && <InlineAlert tone="danger">{error}</InlineAlert>}
      </div>
    </Sheet>
  );
}
