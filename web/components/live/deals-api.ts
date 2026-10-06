/**
 * Wire shapes of the deal-sourcing API (modules/api …/prospect/ProspectController.kt)
 * and the agents sidecar results it passes through. Shared by the board and the deal sheet.
 */

export type Prospect = {
  id: string;
  tenantId: string;
  name: string;
  source: string;
  sector: string | null;
  region: string | null;
  description: string | null;
  registeredAt: string;
  stage: string;
  decidedBy: string | null;
  lastEventAt: string;
};

export type ProspectEvent = {
  seq: number;
  eventType: string;
  stageFrom: string | null;
  stageTo: string | null;
  actor: string;
  rationale: string | null;
  occurredAt: string;
  recordedAt: string;
  correlationId: string;
  taskId: string | null;
};

export type IcReview = { taskId: string; taskStatus: string; requestedBy: string; decidedBy: string | null };

export type ScreenResult = { verdict: "clear" | "review" | "reject"; reasons: string[]; stage: string; reviewTaskId: string | null };

export type AgentScreenResult = {
  status: "completed" | "refused";
  memo: string;
  verdict: { proceed: boolean; proceed_probability: number; confidence: number; rationale_band: string | null } | null;
  screening_requested: boolean;
  stage_note: string | null;
};

export type IcMemoResult = {
  status: "completed" | "refused";
  memo: string;
  verdict: { submit: boolean; complete_probability: number; thesis_band: string | null; evidence_score: number } | null;
  ic_review_requested: boolean;
  ic_review_task_id: string | null;
  stage_note: string | null;
};

/** agents/octo_agents/workflows/due_diligence.py `DdResult`. */
export type DdReviewResult = {
  status: "completed" | "refused";
  dossier: string;
  bands: { workstream: string; band: string; confidence: number }[];
  completeness: number | null;
  tasks: { workstream: string; task_id: string | null; opened: boolean; outcome_unknown?: boolean }[];
  task_errors: string[];
};

/** The open pipeline, in order. `passed` and `invested` are terminal and read separately. */
export const OPEN_STAGES = [
  { id: "sourced", name: "Sourced", does: "Registered from CRM, a referral or by hand. Nothing is judged yet." },
  { id: "screening", name: "Screening", does: "Checked against the mandate's screening rules, with an optional AI first look." },
  { id: "due-diligence", name: "Due diligence", does: "An evidence checklist is open; missing workstreams are flagged as tasks." },
  { id: "ic-review", name: "IC review", does: "The IC memo goes to an approver, who is never the person who requested it." },
] as const;

export const STAGE_NAME: Record<string, string> = {
  ...Object.fromEntries(OPEN_STAGES.map((s) => [s.id, s.name])),
  passed: "Passed",
  invested: "Invested",
};

export const NEXT_STAGE: Record<string, string> = { sourced: "screening", screening: "due-diligence", "due-diligence": "ic-review" };

/** Sources a person can register by hand; `crm` arrives through the CRM import. */
export const MANUAL_SOURCES = [
  { value: "manual", label: "Own origination" },
  { value: "referral", label: "Referral" },
  { value: "inbound", label: "Inbound" },
  { value: "event", label: "Event" },
];

/** Diligence workstreams an evidence request can name (`[a-z][a-z0-9-]*`). */
export const WORKSTREAMS = [
  { value: "financial", label: "Financial" },
  { value: "commercial", label: "Commercial" },
  { value: "legal", label: "Legal" },
  { value: "technical", label: "Technical" },
  { value: "tax", label: "Tax" },
  { value: "esg", label: "ESG" },
];

/** What a card should prompt next, given its stage and IC state. */
export function nextStep(p: Prospect, ic: IcReview | null | undefined): string {
  switch (p.stage) {
    case "sourced":
      return "Next: move to screening";
    case "screening":
      return "Next: run the screen";
    case "due-diligence":
      return "Next: close evidence, then IC";
    case "ic-review":
      if (ic === undefined) return "Checking IC status…";
      if (!ic || ic.taskStatus === "rejected" || ic.taskStatus === "cancelled") return "Next: request IC approval";
      if (ic.taskStatus === "approved") return "Next: record investment";
      return "Awaiting an approver";
    default:
      return "";
  }
}

export const pct = (v: number | undefined | null) => (v === undefined || v === null ? "—" : `${Math.round(v * 100)}%`);
