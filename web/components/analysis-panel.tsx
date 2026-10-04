"use client";

import { useState } from "react";
import { Loader2 } from "lucide-react";
import PanelHeader from "@/components/panel-header";
import { messageFor, postJson } from "@/lib/api";
import { useTenants } from "@/lib/use-tenants";

/**
 * Member triggers for the sealed narrators that previously had no platform
 * caller. Equity bridge posts the operating points; the API computes the
 * bridge and calls `equity-bridge`. DDQ and operating review post the facts
 * those workflows judge. Due diligence is the deal-pipeline action.
 */

type Narrative = {
  status?: string;
  analysis?: string;
  response?: string;
  review?: string;
  note?: string | null;
  computed?: {
    change?: string;
    method?: string;
    effects?: Record<string, string>;
  };
};

type Point = {
  date: string;
  revenue: string;
  margin: string;
  multiple: string;
  netDebt: string;
  fxRate: string;
};

const POINT_FIELDS: { key: keyof Point; label: string }[] = [
  { key: "date", label: "Date" },
  { key: "revenue", label: "Revenue" },
  { key: "margin", label: "EBITDA margin" },
  { key: "multiple", label: "Multiple" },
  { key: "netDebt", label: "Net debt" },
  { key: "fxRate", label: "FX to reporting" },
];

const SEQUENTIAL = ["REVENUE", "MARGIN", "MULTIPLE", "NET_DEBT", "FX"];

const inputClass =
  "h-9 w-full rounded-[var(--rb-r-md,8px)] border border-neutral-200/70 bg-white px-2.5 text-[13px] text-neutral-900 placeholder:text-neutral-400 dark:border-neutral-800 dark:bg-neutral-900 dark:text-neutral-100";

const buttonClass =
  "inline-flex h-9 cursor-pointer items-center gap-2 rounded-[var(--rb-r-md,8px)] bg-[var(--rb-accent)] px-4 text-[13px] font-medium text-[var(--rb-accent-fg)] disabled:cursor-default disabled:opacity-50";

function emptyPoint(): Point {
  return { date: "", revenue: "", margin: "", multiple: "", netDebt: "", fxRate: "1" };
}

function pointReady(point: Point): boolean {
  return POINT_FIELDS.every((field) => point[field.key].trim() !== "");
}

function parseObject(text: string): Record<string, unknown> | null {
  try {
    const value: unknown = JSON.parse(text);
    if (value === null || typeof value !== "object" || Array.isArray(value)) return null;
    return value as Record<string, unknown>;
  } catch {
    return null;
  }
}

function useRun() {
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<Narrative | null>(null);

  async function run(path: string, body: unknown) {
    setPending(true);
    setError(null);
    setResult(null);
    try {
      setResult(await postJson<Narrative>(path, body));
    } catch (e) {
      setError(messageFor(e));
    } finally {
      setPending(false);
    }
  }

  function fail(message: string) {
    setPending(false);
    setResult(null);
    setError(message);
  }

  return { pending, error, result, run, fail };
}

function Outcome({ result }: { result: Narrative | null }) {
  if (!result) return null;
  const prose = result.analysis || result.response || result.review;
  return (
    <section className="mt-3 space-y-2 rounded-[var(--rb-r-lg,10px)] border border-neutral-200/70 p-3 text-[13px] dark:border-neutral-800">
      <p className="font-medium text-neutral-900 dark:text-neutral-100">
        {result.status === "completed" ? "Completed" : result.status === "refused" ? "Refused" : result.status}
      </p>
      {result.note && <p className="text-neutral-500">{result.note}</p>}
      {result.computed && (
        <p className="text-neutral-500">
          Computed change {result.computed.change} ({result.computed.method})
          {result.computed.effects &&
            ` — ${Object.entries(result.computed.effects)
              .map(([driver, effect]) => `${driver} ${effect}`)
              .join(", ")}`}
        </p>
      )}
      {prose && <p className="whitespace-pre-wrap text-neutral-800 dark:text-neutral-200">{prose}</p>}
    </section>
  );
}

function EquityBridge({ tenantId }: { tenantId: string }) {
  const { pending, error, result, run } = useRun();
  const [company, setCompany] = useState("");
  const [localCurrency, setLocalCurrency] = useState("USD");
  const [reportingCurrency, setReportingCurrency] = useState("USD");
  const [method, setMethod] = useState<"shapley" | "sequential">("shapley");
  const [entry, setEntry] = useState<Point>(emptyPoint);
  const [exit, setExit] = useState<Point>(emptyPoint);
  const ready =
    company.trim() !== "" &&
    /^[A-Za-z]{3}$/.test(localCurrency) &&
    /^[A-Za-z]{3}$/.test(reportingCurrency) &&
    pointReady(entry) &&
    pointReady(exit);

  return (
    <form
      className="space-y-3"
      onSubmit={(e) => {
        e.preventDefault();
        if (!ready || pending) return;
        void run("/api/v1/analytics/equity-bridge", {
          tenantId,
          company: company.trim(),
          localCurrency: localCurrency.toUpperCase(),
          reportingCurrency: reportingCurrency.toUpperCase(),
          method,
          ordering: method === "sequential" ? SEQUENTIAL : [],
          entry,
          exit,
        });
      }}
    >
      <h2 className="text-[13px] font-medium text-neutral-900 dark:text-neutral-100">Equity bridge</h2>
      <p className="text-[13px] text-neutral-500">
        The platform attributes the change, then the equity-bridge workflow narrates those figures.
      </p>
      <div className="grid gap-2 sm:grid-cols-2">
        <label className="flex flex-col gap-1">
          <span className="text-[12px] text-neutral-500">Company</span>
          <input className={inputClass} value={company} maxLength={200} onChange={(e) => setCompany(e.target.value)} />
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-[12px] text-neutral-500">Method</span>
          <select className={inputClass} value={method} onChange={(e) => setMethod(e.target.value as "shapley" | "sequential")}>
            <option value="shapley">Shapley</option>
            <option value="sequential">Sequential (revenue, margin, multiple, net debt, FX)</option>
          </select>
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-[12px] text-neutral-500">Local currency</span>
          <input className={inputClass} value={localCurrency} maxLength={3} onChange={(e) => setLocalCurrency(e.target.value)} />
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-[12px] text-neutral-500">Reporting currency</span>
          <input
            className={inputClass}
            value={reportingCurrency}
            maxLength={3}
            onChange={(e) => setReportingCurrency(e.target.value)}
          />
        </label>
      </div>
      {(["Entry", "Exit"] as const).map((label) => {
        const point = label === "Entry" ? entry : exit;
        const setPoint = label === "Entry" ? setEntry : setExit;
        return (
          <fieldset key={label} className="space-y-2">
            <legend className="text-[12px] text-neutral-500">{label}</legend>
            <div className="grid gap-2 sm:grid-cols-3">
              {POINT_FIELDS.map((field) => (
                <label key={field.key} className="flex flex-col gap-1">
                  <span className="text-[12px] text-neutral-500">{field.label}</span>
                  <input
                    className={inputClass}
                    type={field.key === "date" ? "date" : "text"}
                    inputMode={field.key === "date" ? undefined : "decimal"}
                    value={point[field.key]}
                    onChange={(e) => setPoint({ ...point, [field.key]: e.target.value })}
                  />
                </label>
              ))}
            </div>
          </fieldset>
        );
      })}
      {error && <p className="text-[13px] text-red-600">{error}</p>}
      <button type="submit" className={buttonClass} disabled={!ready || pending}>
        {pending && <Loader2 aria-hidden className="h-4 w-4 animate-spin motion-reduce:animate-none" />}
        {pending ? "Running…" : "Narrate bridge"}
      </button>
      <Outcome result={result} />
    </form>
  );
}

function DdqResponse({ tenantId }: { tenantId: string }) {
  const { pending, error, result, run, fail } = useRun();
  const [subject, setSubject] = useState("");
  const [questions, setQuestions] = useState("");
  const [facts, setFacts] = useState("{}");
  const parsed = questions
    .split("\n")
    .map((line) => line.trim())
    .filter(Boolean);
  const ready = subject.trim() !== "" && parsed.length > 0;

  return (
    <form
      className="space-y-3"
      onSubmit={(e) => {
        e.preventDefault();
        if (!ready || pending) return;
        const firmFacts = parseObject(facts);
        if (!firmFacts) {
          fail("Firm facts must be a JSON object.");
          return;
        }
        void run("/api/v1/fundraising/ddq-response", {
          tenantId,
          subject: subject.trim(),
          questions: parsed,
          facts: firmFacts,
        });
      }}
    >
      <h2 className="text-[13px] font-medium text-neutral-900 dark:text-neutral-100">DDQ response</h2>
      <p className="text-[13px] text-neutral-500">
        Answers use only the firm facts below. A question the materials do not cover stays unanswered rather than invented.
      </p>
      <label className="flex flex-col gap-1">
        <span className="text-[12px] text-neutral-500">Questionnaire</span>
        <input className={inputClass} value={subject} maxLength={200} onChange={(e) => setSubject(e.target.value)} />
      </label>
      <label className="flex flex-col gap-1">
        <span className="text-[12px] text-neutral-500">Questions, one per line</span>
        <textarea className={`${inputClass} h-24 py-2`} value={questions} onChange={(e) => setQuestions(e.target.value)} />
      </label>
      <label className="flex flex-col gap-1">
        <span className="text-[12px] text-neutral-500">Firm facts (JSON object)</span>
        <textarea className={`${inputClass} h-24 py-2 font-mono`} value={facts} onChange={(e) => setFacts(e.target.value)} />
      </label>
      {error && <p className="text-[13px] text-red-600">{error}</p>}
      <button type="submit" className={buttonClass} disabled={!ready || pending}>
        {pending && <Loader2 aria-hidden className="h-4 w-4 animate-spin motion-reduce:animate-none" />}
        {pending ? "Drafting…" : "Draft DDQ response"}
      </button>
      <Outcome result={result} />
    </form>
  );
}

function OperatingReview({ tenantId }: { tenantId: string }) {
  const { pending, error, result, run, fail } = useRun();
  const [company, setCompany] = useState("");
  const [levers, setLevers] = useState("");
  const [metrics, setMetrics] = useState("{}");
  const parsed = levers
    .split("\n")
    .map((line) => line.trim())
    .filter(Boolean);
  const ready = company.trim() !== "" && parsed.length > 0 && metrics.trim() !== "" && metrics.trim() !== "{}";

  return (
    <form
      className="space-y-3"
      onSubmit={(e) => {
        e.preventDefault();
        if (!ready || pending) return;
        const series = parseObject(metrics);
        if (!series) {
          fail("Period metrics must be a JSON object.");
          return;
        }
        void run("/api/v1/portfolio/operating-review", {
          tenantId,
          company: company.trim(),
          levers: parsed,
          metrics: series,
        });
      }}
    >
      <h2 className="text-[13px] font-medium text-neutral-900 dark:text-neutral-100">Operating review</h2>
      <p className="text-[13px] text-neutral-500">
        Reviews each value-creation lever against the period metrics. The draft stays analytical.
      </p>
      <label className="flex flex-col gap-1">
        <span className="text-[12px] text-neutral-500">Company</span>
        <input className={inputClass} value={company} maxLength={200} onChange={(e) => setCompany(e.target.value)} />
      </label>
      <label className="flex flex-col gap-1">
        <span className="text-[12px] text-neutral-500">Levers, one per line</span>
        <textarea className={`${inputClass} h-20 py-2`} value={levers} onChange={(e) => setLevers(e.target.value)} />
      </label>
      <label className="flex flex-col gap-1">
        <span className="text-[12px] text-neutral-500">Period metrics (JSON object)</span>
        <textarea className={`${inputClass} h-24 py-2 font-mono`} value={metrics} onChange={(e) => setMetrics(e.target.value)} />
      </label>
      {error && <p className="text-[13px] text-red-600">{error}</p>}
      <button type="submit" className={buttonClass} disabled={!ready || pending}>
        {pending && <Loader2 aria-hidden className="h-4 w-4 animate-spin motion-reduce:animate-none" />}
        {pending ? "Reviewing…" : "Run operating review"}
      </button>
      <Outcome result={result} />
    </form>
  );
}

export default function AnalysisPanel() {
  const { tenantId, tenants } = useTenants();
  const role = tenants.find((t) => t.tenantId === tenantId)?.role;
  const canWrite = Boolean(tenantId) && role !== undefined && role !== "viewer";

  return (
    <div className="max-w-2xl space-y-8">
      <PanelHeader description="Quarter-end equity bridge, LP questionnaire drafts, and portfolio operating reviews. Each one calls its workflow and shows the judged result." />
      {canWrite ? (
        <>
          <EquityBridge tenantId={tenantId} />
          <DdqResponse tenantId={tenantId} />
          <OperatingReview tenantId={tenantId} />
        </>
      ) : (
        <p className="text-[13px] text-neutral-500">Members of this workspace run these workflows.</p>
      )}
    </div>
  );
}
