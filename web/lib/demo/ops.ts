/**
 * Demo operational objects: alerts and rules, reconciliation breaks, tasks,
 * approvals, exceptions, AI drafts, activity, notifications, and the
 * intelligence feed. Illustrative only — always labelled as demo in the UI.
 */

import { at, daysAgo, type Severity } from "./entities";

export type EntityRef = { type: "Fund" | "Company" | "Investment" | "Deal" | "Source" | "Report" | "Portfolio"; id: string; name: string };

export const ref = {
  fund: (id: string, name: string): EntityRef => ({ type: "Fund", id, name }),
  company: (id: string, name: string): EntityRef => ({ type: "Company", id, name }),
  deal: (id: string, name: string): EntityRef => ({ type: "Deal", id, name }),
  source: (id: string, name: string): EntityRef => ({ type: "Source", id, name }),
};

/**
 * Route for any ontology reference — drill-down preserves context (plan §23).
 * Deals and reports are served live by the OCTO API, which has no record for a
 * demo id, so those references open the live list instead of a detail page.
 */
export function hrefFor(e: EntityRef): string | undefined {
  switch (e.type) {
    case "Fund":
      return `/app/funds/${e.id.toLowerCase()}`;
    case "Company":
      return `/app/companies/${e.id.toLowerCase()}`;
    case "Deal":
      return "/app/deals";
    case "Source":
      return `/app/data?source=${e.id}`;
    case "Report":
      return "/app/reports";
    default:
      return undefined;
  }
}

/* ---------- Alert rules ---------- */

export type Rule = {
  id: string;
  name: string;
  category: "Covenant" | "Valuation" | "Concentration" | "Performance" | "Data quality" | "News";
  definition: string;
  conditions: string[];
  sources: string[];
  severity: Severity;
  active: boolean;
  version: number;
  lastTrigger?: string;
  affected: number;
  backtest: { period: string; triggers: number; precision: number };
  owner: string;
};

export const RULES: Rule[] = [
  { id: "RUL-018", name: "Debt service cover", category: "Covenant", definition: "DSCR from the latest compliance certificate falls below the facility covenant.", conditions: ["DSCR < covenant (1.20×)", "Certificate age ≤ 45 days"], sources: ["Compliance certificates", "Loan register"], severity: "critical", active: true, version: 4, lastTrigger: at(2), affected: 1, backtest: { period: "2022–2026", triggers: 6, precision: 100 }, owner: "Risk" },
  { id: "RUL-012", name: "Leverage ceiling", category: "Covenant", definition: "Net debt / LTM EBITDA exceeds the investment-policy ceiling.", conditions: ["Net debt / EBITDA > 5.5×"], sources: ["Management accounts", "IBOR"], severity: "high", active: true, version: 2, lastTrigger: at(9), affected: 1, backtest: { period: "2022–2026", triggers: 11, precision: 91 }, owner: "Risk" },
  { id: "RUL-007", name: "Mark freshness", category: "Valuation", definition: "An unrealised position has no approved valuation within the policy window.", conditions: ["Days since approved mark > 30"], sources: ["IBOR valuation log"], severity: "medium", active: true, version: 1, lastTrigger: at(6), affected: 3, backtest: { period: "2023–2026", triggers: 24, precision: 100 }, owner: "Valuation" },
  { id: "RUL-003", name: "Sector limit", category: "Concentration", definition: "Look-through sector exposure approaches or breaches the LPA limit.", conditions: ["Sector weight ≥ 24% (warn)", "Sector weight ≥ 25% (breach)"], sources: ["Look-through exposure"], severity: "medium", active: true, version: 3, lastTrigger: at(12), affected: 1, backtest: { period: "2021–2026", triggers: 4, precision: 100 }, owner: "Risk" },
  { id: "RUL-021", name: "Budget variance", category: "Performance", definition: "Quarterly revenue misses budget by more than the threshold.", conditions: ["Revenue vs budget < −10%"], sources: ["Management accounts", "Budgets"], severity: "high", active: true, version: 1, lastTrigger: at(20), affected: 1, backtest: { period: "2023–2026", triggers: 17, precision: 76 }, owner: "Portfolio ops" },
  { id: "RUL-009", name: "FX hedge ratio", category: "Concentration", definition: "Unhedged non-USD exposure exceeds treasury policy.", conditions: ["Unhedged share > 35%"], sources: ["Treasury positions"], severity: "low", active: true, version: 2, lastTrigger: at(30), affected: 1, backtest: { period: "2024–2026", triggers: 9, precision: 89 }, owner: "Treasury" },
  { id: "RUL-030", name: "Source completeness", category: "Data quality", definition: "An expected administrator or custodian file is missing past its SLA.", conditions: ["File not received by T+5"], sources: ["Ingestion monitor"], severity: "high", active: true, version: 1, lastTrigger: at(52), affected: 1, backtest: { period: "2025–2026", triggers: 7, precision: 100 }, owner: "Data ops" },
  { id: "RUL-040", name: "Leadership change", category: "News", definition: "News or filings report a C-level change at a portfolio company.", conditions: ["Entity match ≥ 0.9", "Role ∈ {CEO, CFO, COO}"], sources: ["News feed", "Company filings"], severity: "medium", active: true, version: 2, lastTrigger: at(96), affected: 1, backtest: { period: "2024–2026", triggers: 13, precision: 69 }, owner: "Deal team" },
  { id: "RUL-044", name: "Cash runway", category: "Performance", definition: "Months of runway at current burn falls below the threshold.", conditions: ["Runway < 9 months"], sources: ["Management accounts"], severity: "high", active: false, version: 1, affected: 0, backtest: { period: "2025–2026", triggers: 3, precision: 67 }, owner: "Portfolio ops" },
];

/* ---------- Alerts ---------- */

export type AlertState = "Open" | "Acknowledged" | "Snoozed" | "Resolved";

export type Alert = {
  id: string;
  title: string;
  severity: Severity;
  state: AlertState;
  entity: EntityRef;
  ruleId: string;
  owner: string;
  triggeredAt: string;
  observed: string;
  threshold: string;
  source: string;
};

export const ALERTS: Alert[] = [
  { id: "ALR-1841", title: "Covenant breach · DSCR below 1.20×", severity: "critical", state: "Open", entity: ref.company("CMP-0211", "Helios Data Centers"), ruleId: "RUL-018", owner: "R. Tan", triggeredAt: at(2), observed: "1.14×", threshold: "≥ 1.20×", source: "Q3 compliance certificate" },
  { id: "ALR-1838", title: "Leverage above 5.5× net debt / EBITDA", severity: "high", state: "Open", entity: ref.company("CMP-0192", "Kirana Consumer"), ruleId: "RUL-012", owner: "A. Wijaya", triggeredAt: at(9), observed: "5.8×", threshold: "≤ 5.5×", source: "Management accounts Aug" },
  { id: "ALR-1835", title: "Valuation stale · 45 days", severity: "medium", state: "Acknowledged", entity: ref.company("CMP-0215", "Solus Energy Partners"), ruleId: "RUL-007", owner: "Valuation team", triggeredAt: at(6), observed: "45 days", threshold: "≤ 30 days", source: "IBOR valuation log" },
  { id: "ALR-1829", title: "Sector concentration near limit", severity: "medium", state: "Open", entity: ref.fund("FND-002", "OCTO Flagship Fund II"), ruleId: "RUL-003", owner: "Risk", triggeredAt: at(12), observed: "24.1%", threshold: "≤ 25.0% (warn at 24%)", source: "Look-through exposure" },
  { id: "ALR-1826", title: "Revenue miss vs budget > 10%", severity: "high", state: "Acknowledged", entity: ref.company("CMP-0187", "Meridian Health"), ruleId: "RUL-021", owner: "Portfolio ops", triggeredAt: at(20), observed: "−11.4%", threshold: "≥ −10%", source: "Q3 management accounts" },
  { id: "ALR-1822", title: "FX exposure unhedged above policy", severity: "low", state: "Open", entity: ref.fund("FND-003", "OCTO Opportunities I"), ruleId: "RUL-009", owner: "Treasury", triggeredAt: at(30), observed: "38% unhedged", threshold: "≤ 35%", source: "Treasury positions" },
  { id: "ALR-1820", title: "Valuation stale · 33 days", severity: "low", state: "Snoozed", entity: ref.company("CMP-0225", "Sinar Agritech"), ruleId: "RUL-007", owner: "Valuation team", triggeredAt: at(40), observed: "33 days", threshold: "≤ 30 days", source: "IBOR valuation log" },
  { id: "ALR-1817", title: "Missing Q3 administrator file", severity: "high", state: "Resolved", entity: ref.fund("FND-005", "OCTO Venture FoF"), ruleId: "RUL-030", owner: "Data ops", triggeredAt: at(52), observed: "File not received", threshold: "By T+5", source: "Ingestion monitor" },
  { id: "ALR-1809", title: "Key person departure reported", severity: "medium", state: "Resolved", entity: ref.company("CMP-0198", "Aruna Payments"), ruleId: "RUL-040", owner: "D. Lim", triggeredAt: at(96), observed: "CFO resignation", threshold: "Any C-level change", source: "News feed" },
];

/* ---------- Reconciliation ---------- */

export type ReconState = "Open" | "Investigating" | "Escalated" | "Resolved";

export type ReconBreak = {
  id: string;
  entity: EntityRef;
  field: string;
  source: string;
  sourceValue: string;
  iborValue: string;
  variance: string;
  varianceAbs: number;
  ageHours: number;
  severity: Severity;
  owner: string;
  state: ReconState;
  cause: string;
  mappingRule: string;
  sourceTimestamp: string;
  previousResolution?: string;
  ledgerEvents: { id: string; label: string; at: string }[];
};

export const RECON: ReconBreak[] = [
  { id: "REC-2207", entity: ref.fund("FND-002", "OCTO Flagship Fund II"), field: "Cash · USD operating", source: "Administrator", sourceValue: "$2,418,200", iborValue: "$2,398,200", variance: "$20,000", varianceAbs: 20000, ageHours: 4, severity: "high", owner: "Fund accounting", state: "Open", cause: "Unbooked FX settlement on 29 Sep", mappingRule: "MAP-114 · admin cash → IBOR cash (USD)", sourceTimestamp: at(6), previousResolution: "REC-2071 · same account, FX settlement timing (Accept IBOR)", ledgerEvents: [{ id: "LED-88412", label: "FX spot USD/IDR settlement", at: at(30) }, { id: "LED-88390", label: "Management fee payment", at: at(52) }] },
  { id: "REC-2204", entity: ref.company("CMP-0203", "Serayu Renewables"), field: "Position quantity", source: "Administrator", sourceValue: "1,212,400 sh", iborValue: "1,200,000 sh", variance: "12,400 sh", varianceAbs: 180000, ageHours: 26, severity: "medium", owner: "Fund accounting", state: "Investigating", cause: "Stock dividend not yet processed in IBOR", mappingRule: "MAP-031 · admin holdings → IBOR positions", sourceTimestamp: at(28), ledgerEvents: [{ id: "LED-88201", label: "Corporate action announced: stock dividend 1.033%", at: at(70) }] },
  { id: "REC-2199", entity: ref.company("CMP-0198", "Aruna Payments"), field: "Trade confirm", source: "Custodian", sourceValue: "—", iborValue: "$4,000,000 call", variance: "Missing", varianceAbs: 4000000, ageHours: 70, severity: "low", owner: "Fund accounting", state: "Open", cause: "Confirm not received from custodian", mappingRule: "MAP-207 · custodian confirms → IBOR trades", sourceTimestamp: at(72), ledgerEvents: [{ id: "LED-87954", label: "Capital call booked", at: at(74) }] },
  { id: "REC-2196", entity: ref.fund("FND-001", "OCTO Flagship Fund I"), field: "Management fee accrual", source: "Administrator", sourceValue: "$1,184,000", iborValue: "$1,176,500", variance: "$7,500", varianceAbs: 7500, ageHours: 120, severity: "medium", owner: "Fund accounting", state: "Escalated", cause: "Day-count basis differs (ACT/360 vs ACT/365)", mappingRule: "MAP-090 · admin accruals → IBOR accruals", sourceTimestamp: at(122), previousResolution: "REC-1988 · Fix mapping (day count)", ledgerEvents: [{ id: "LED-87611", label: "Q3 fee accrual", at: at(130) }] },
  { id: "REC-2191", entity: ref.fund("FND-003", "OCTO Opportunities I"), field: "FX rate · IDR/USD close", source: "Market data", sourceValue: "16,280", iborValue: "16,215", variance: "0.40%", varianceAbs: 26000, ageHours: 48, severity: "low", owner: "Data ops", state: "Open", cause: "Feed published a stale print", mappingRule: "MAP-012 · vendor FX → IBOR FX", sourceTimestamp: at(50), ledgerEvents: [] },
  { id: "REC-2188", entity: ref.company("CMP-0181", "Lumen Education"), field: "Distribution received", source: "Bank", sourceValue: "$2,100,000", iborValue: "$2,100,000", variance: "$0", varianceAbs: 0, ageHours: 160, severity: "low", owner: "Fund accounting", state: "Resolved", cause: "Timing — booked next day", mappingRule: "MAP-150 · bank statements → IBOR cash", sourceTimestamp: at(170), ledgerEvents: [{ id: "LED-87402", label: "Dividend receipt", at: at(166) }] },
];

/* ---------- Tasks, approvals, exceptions ---------- */

export type Task = { id: string; title: string; entity: EntityRef; type: string; priority: Severity; due: string; status: "To do" | "In progress" | "Blocked" | "Done"; assignee: string };

export const TASKS: Task[] = [
  { id: "TSK-3120", title: "Review IC memo — Kirana add-on", entity: ref.deal("DL-0301", "Kirana Consumer (add-on)"), type: "Approval", priority: "high", due: "30 Sep", status: "To do", assignee: "You" },
  { id: "TSK-3118", title: "Review AI variance draft", entity: ref.company("CMP-0187", "Meridian Health"), type: "Review", priority: "medium", due: "1 Oct", status: "To do", assignee: "You" },
  { id: "TSK-3114", title: "Sign off Q3 NAV for Flagship II", entity: ref.fund("FND-002", "OCTO Flagship Fund II"), type: "Sign-off", priority: "high", due: "2 Oct", status: "In progress", assignee: "You" },
  { id: "TSK-3109", title: "Collect EBITDA bridge evidence", entity: ref.deal("DL-0302", "Aruna Payments (Series D)"), type: "Evidence", priority: "medium", due: "3 Oct", status: "Blocked", assignee: "You" },
  { id: "TSK-3101", title: "Update Helios lender call notes", entity: ref.company("CMP-0211", "Helios Data Centers"), type: "Follow-up", priority: "critical", due: "30 Sep", status: "In progress", assignee: "You" },
  { id: "TSK-3096", title: "Confirm Serayu share count with admin", entity: ref.company("CMP-0203", "Serayu Renewables"), type: "Recon", priority: "medium", due: "4 Oct", status: "To do", assignee: "You" },
  { id: "TSK-3090", title: "Refresh Solus valuation model", entity: ref.company("CMP-0215", "Solus Energy Partners"), type: "Valuation", priority: "medium", due: "5 Oct", status: "To do", assignee: "Valuation team" },
];

export type Approval = { id: string; title: string; entity: EntityRef; kind: string; requestedBy: string; requestedAt: string; due: string; progress: string; summary: string; steps: { label: string; state: "done" | "current" | "todo" }[] };

export const APPROVALS: Approval[] = [
  { id: "APR-0412", title: "IC memo · Kirana Consumer add-on", entity: ref.deal("DL-0301", "Kirana Consumer (add-on)"), kind: "Investment committee", requestedBy: "A. Wijaya", requestedAt: at(20), due: "Today", progress: "2 of 3 approvals", summary: "$18.0M add-on at 7.2× EBITDA; funded from Flagship II reserves. Leverage alert ALR-1838 open.", steps: [{ label: "Deal team", state: "done" }, { label: "Risk", state: "done" }, { label: "IC chair", state: "current" }] },
  { id: "APR-0409", title: "LP report · Q3 pack Flagship II", entity: ref.fund("FND-002", "OCTO Flagship Fund II"), kind: "LP report", requestedBy: "Investor relations", requestedAt: at(48), due: "2 Oct", progress: "0 of 1 approvals", summary: "Generated from reconciled IBOR as of 30 Sep. Two open recon breaks disclosed in notes.", steps: [{ label: "Draft", state: "done" }, { label: "CFO", state: "current" }, { label: "Publish", state: "todo" }] },
  { id: "APR-0407", title: "Data override · FX rate IDR/USD", entity: ref.source("SRC-MKT", "Market data feed"), kind: "Data override", requestedBy: "Data ops", requestedAt: at(52), due: "1 Oct", progress: "0 of 1 approvals", summary: "Manual rate 16,215 vs feed 16,280 for 30 Sep close; the feed published a stale print.", steps: [{ label: "Data ops", state: "done" }, { label: "Controller", state: "current" }] },
  { id: "APR-0401", title: "Capital call · Opportunities I #14", entity: ref.fund("FND-003", "OCTO Opportunities I"), kind: "Capital call", requestedBy: "Fund accounting", requestedAt: at(70), due: "5 Oct", progress: "1 of 2 approvals", summary: "$12.5M call for the Solus follow-on and fees; notices drafted for 38 LPs.", steps: [{ label: "Fund accounting", state: "done" }, { label: "CFO", state: "current" }, { label: "Notices sent", state: "todo" }] },
];

export type ExceptionItem = { id: string; kind: "Valuation stale" | "Covenant" | "Recon break" | "Approval overdue" | "Data missing"; title: string; entity: EntityRef; severity: Severity; age: string; action: string; href: string };

export const EXCEPTIONS: ExceptionItem[] = [
  { id: "EXC-01", kind: "Covenant", title: "DSCR 1.14× vs 1.20×", entity: ref.company("CMP-0211", "Helios Data Centers"), severity: "critical", age: "2h", action: "Review", href: "/app/alerts?id=ALR-1841" },
  { id: "EXC-02", kind: "Covenant", title: "Leverage 5.8× vs 5.5×", entity: ref.company("CMP-0192", "Kirana Consumer"), severity: "high", age: "9h", action: "Review", href: "/app/alerts?id=ALR-1838" },
  { id: "EXC-03", kind: "Valuation stale", title: "No approved mark in 45 days", entity: ref.company("CMP-0215", "Solus Energy Partners"), severity: "medium", age: "6h", action: "Request mark", href: "/app/companies/cmp-0215" },
  { id: "EXC-04", kind: "Valuation stale", title: "No approved mark in 33 days", entity: ref.company("CMP-0225", "Sinar Agritech"), severity: "low", age: "2d", action: "Request mark", href: "/app/companies/cmp-0225" },
  { id: "EXC-05", kind: "Recon break", title: "Cash variance $20,000", entity: ref.fund("FND-002", "OCTO Flagship Fund II"), severity: "high", age: "4h", action: "Resolve", href: "/app/reconciliation" },
  { id: "EXC-06", kind: "Recon break", title: "Position qty 12,400 sh", entity: ref.company("CMP-0203", "Serayu Renewables"), severity: "medium", age: "1d", action: "Resolve", href: "/app/reconciliation" },
  { id: "EXC-07", kind: "Approval overdue", title: "IC memo due today", entity: ref.deal("DL-0301", "Kirana Consumer (add-on)"), severity: "high", age: "20h", action: "Approve", href: "/app/workflows?tab=approvals&id=APR-0412" },
];

/* ---------- AI drafts ---------- */

export type Verification = "verified" | "source-derived" | "ai-suggested" | "human-reviewed" | "pending-review" | "unsupported";

export type AiDraft = {
  id: string;
  title: string;
  entity: EntityRef;
  kind: string;
  confidence: "High" | "Medium" | "Low";
  verification: Verification;
  body: string;
  citations: { label: string; locator: string; asOf: string }[];
  model: string;
  createdAt: string;
  /** What a reviewer can inspect: inputs, deterministic checks and the action log. Never model reasoning. */
  trace: {
    inputs: { label: string; detail: string }[];
    checks: { label: string; result: "pass" | "warn" | "fail"; detail: string }[];
    actions: { at: string; actor: string; text: string }[];
  };
};

export const AI_DRAFTS: AiDraft[] = [
  {
    id: "AID-0310",
    title: "Q3 variance explanation",
    entity: ref.company("CMP-0187", "Meridian Health"),
    kind: "Variance explanation",
    confidence: "Medium",
    verification: "pending-review",
    body: "Meridian Health EBITDA fell 6.1% quarter on quarter to $14.2M, driven by a one-off $1.1M ward refurbishment expensed in August and a 2.4-point rise in nurse agency costs. Revenue grew 3.8% on higher outpatient volumes. Management expects agency costs to normalise by Q1 2027 as 42 permanent hires start.",
    citations: [
      { label: "Q3 management accounts", locator: "p. 4, table 2", asOf: at(30) },
      { label: "August board pack", locator: "p. 14", asOf: at(310) },
      { label: "Payroll extract Sep", locator: "rows 1–212", asOf: at(26) },
      { label: "IBOR valuation Q3", locator: "VAL-2291", asOf: at(11) },
    ],
    model: "octo-analyst v2.1 (self-hosted)",
    createdAt: at(9),
    trace: {
      inputs: [
        { label: "Meridian Health · Q3 management accounts", detail: "PDF, 18 pages, uploaded by fund accounting" },
        { label: "IBOR valuation VAL-2291", detail: "Q3 2026 mark, approved" },
        { label: "Prior-quarter financials", detail: "Q2 2026, reconciled" },
        { label: "Template", detail: "Variance explanation v3 (house style)" },
      ],
      checks: [
        { label: "Figures reconcile to IBOR", result: "pass", detail: "EBITDA $14.2M and revenue match VAL-2291 within $0.05M." },
        { label: "Every claim has a citation", result: "pass", detail: "4 of 4 numeric claims cite a source page or record." },
        { label: "Forward-looking statement", result: "warn", detail: "“normalise by Q1 2027” is a management expectation; keep the attribution." },
        { label: "No personal data", result: "pass", detail: "Payroll rows aggregated; no names in the draft." },
      ],
      actions: [
        { at: at(9), actor: "octo-analyst v2.1", text: "Generated draft from 4 inputs" },
        { at: at(9), actor: "OCTO checks", text: "Ran 4 checks · 1 warning" },
        { at: at(9), actor: "Workflow", text: "Routed to you for review" },
      ],
    },
  },
  {
    id: "AID-0311",
    title: "Covenant breach summary for lender call",
    entity: ref.company("CMP-0211", "Helios Data Centers"),
    kind: "Briefing",
    confidence: "High",
    verification: "pending-review",
    body: "DSCR was 1.14× for the twelve months to August against a 1.20× covenant. Interest cost rose 22% after the July refinancing while revenue was flat at $8.0M a month. A 50 bp margin step-down applies from Q1 2027 if DSCR recovers above 1.25×. Recommend requesting a one-quarter waiver.",
    citations: [
      { label: "Q3 compliance certificate", locator: "§3.1", asOf: at(20) },
      { label: "Facility agreement", locator: "cl. 21.2", asOf: at(2200) },
    ],
    model: "octo-analyst v2.1 (self-hosted)",
    createdAt: at(3),
    trace: {
      inputs: [
        { label: "Helios · Q3 compliance certificate", detail: "PDF, 6 pages, from the lender portal" },
        { label: "Facility agreement", detail: "Executed 2024, clause 21 covenants" },
        { label: "Alert ALR-1841", detail: "DSCR below covenant, opened 2h ago" },
      ],
      checks: [
        { label: "Covenant figures match the certificate", result: "pass", detail: "DSCR 1.14× and threshold 1.20× read from §3.1." },
        { label: "Every claim has a citation", result: "pass", detail: "3 of 3 claims cite the certificate or facility agreement." },
        { label: "Recommendation flagged for human decision", result: "pass", detail: "Waiver request is marked as a recommendation, not an action." },
      ],
      actions: [
        { at: at(3), actor: "octo-analyst v2.1", text: "Generated briefing from 3 inputs" },
        { at: at(3), actor: "OCTO checks", text: "Ran 3 checks · all passed" },
        { at: at(3), actor: "Workflow", text: "Routed to you for review" },
      ],
    },
  },
];

/* ---------- Activity, notifications, intelligence ---------- */

/** Activity state drives the timeline marker: complete blue · pending grey · attention amber · critical red (ACT-001). */
export type ActivityState = "complete" | "pending" | "attention" | "critical";

export const ACTIVITY: { id: string; actor: string; verb: string; object: string; at: string; state: ActivityState }[] = [
  { id: "ACT-1", actor: "Q3 NAV model", verb: "completed a run for", object: "5 funds", at: at(0.3), state: "complete" },
  { id: "ACT-2", actor: "M. Sari", verb: "approved the IC memo for", object: "Garuda Fibre follow-on", at: at(5), state: "complete" },
  { id: "ACT-3", actor: "Fund accounting", verb: "resolved a cash break on", object: "Flagship Fund I", at: at(7), state: "complete" },
  { id: "ACT-4", actor: "OCTO", verb: "is waiting on the administrator file for", object: "Flagship Fund II", at: at(11), state: "pending" },
  { id: "ACT-7", actor: "OCTO", verb: "flagged a stale valuation on", object: "Kirana Consumer", at: at(14), state: "attention" },
  { id: "ACT-5", actor: "R. Tan", verb: "raised a covenant alert on", object: "Helios Data Centers", at: at(26), state: "critical" },
  { id: "ACT-6", actor: "D. Lim", verb: "moved to Due Diligence:", object: "Aruna Payments (Series D)", at: at(30), state: "complete" },
];

export const NOTIFICATIONS = [
  { id: "N-1", severity: "critical" as Severity, title: "Covenant breach — Helios Data Centers", entity: "Helios Data Centers", at: at(2), href: "/app/alerts?id=ALR-1841", unread: true },
  { id: "N-2", severity: "high" as Severity, title: "IC memo awaiting your approval", entity: "Kirana Consumer", at: at(20), href: "/app/workflows?tab=approvals&id=APR-0412", unread: true },
  { id: "N-3", severity: "medium" as Severity, title: "AI draft ready for review", entity: "Meridian Health", at: at(9), href: "/app/workflows?tab=ai", unread: true },
  { id: "N-4", severity: "low" as Severity, title: "Q3 NAV model completed", entity: "Portfolio", at: at(0.3), href: "/app/analytics", unread: false },
];

export type Signal = { id: string; kind: "Market signal" | "Company news" | "Portfolio update" | "Diligence signal" | "Regulatory event" | "Macro signal"; title: string; body: string; at: string; entity?: EntityRef; source: string };

export const SIGNALS: Signal[] = [
  { id: "SIG-1", kind: "Company news", title: "Meridian Health named preferred bidder in Ministry tender", body: "Tender notice lists Meridian for three provincial hospital PPPs.", at: at(18), entity: ref.company("CMP-0187", "Meridian Health"), source: "Ministry of Health notice" },
  { id: "SIG-2", kind: "Portfolio update", title: "Helios DSCR trending below covenant for 3 months", body: "Interest cost up 22% after the July refinancing; revenue flat.", at: at(3), entity: ref.company("CMP-0211", "Helios Data Centers"), source: "OCTO observation" },
  { id: "SIG-3", kind: "Macro signal", title: "Bank Indonesia holds rate at 5.75%", body: "Rupiah steady at 16,215; bond yields down 8 bp on the week.", at: at(28), source: "Central bank release" },
  { id: "SIG-4", kind: "Regulatory event", title: "Vietnam finalises DPPA mechanism for renewables", body: "Direct PPAs allowed for C&I buyers above 200 MWh a month.", at: at(40), source: "Government decree" },
  { id: "SIG-5", kind: "Diligence signal", title: "Arus Grid Storage: supplier warranty terms updated", body: "Cell supplier reduces degradation guarantee from 70% to 65% at year 10.", at: at(55), entity: ref.deal("DL-0309", "Arus Grid Storage"), source: "Data room" },
  { id: "SIG-6", kind: "Market signal", title: "ASEAN data-centre capacity pricing firm", body: "Jakarta wholesale colocation up 4% year on year.", at: at(70), source: "Broker research" },
];

export const TODAY_ISO = daysAgo(0);
