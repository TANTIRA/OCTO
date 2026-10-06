/** Wire shape of an agent run (modules/api …/agents/AgentRunsController.kt `AgentRun.view`). */
export type AgentRun = {
  id: string;
  tenantId: string;
  workflow: string;
  runKey: string;
  subjectType: string;
  subjectId: string;
  status: "running" | "completed" | "failed" | "refused" | string;
  actor: string;
  input: unknown;
  output: unknown;
  verdict: Record<string, unknown> | null;
  models: Record<string, string> | null;
  thresholds: unknown;
  requestIds: unknown;
  error: string | null;
  humanOutcome: Record<string, unknown> | null;
  createdAt: string;
  finishedAt: string | null;
};

/** Where a run's subject lives in the app, so a reader can go from "what the AI did" to "what it was about". */
export function subjectLink(r: Pick<AgentRun, "workflow" | "subjectType" | "subjectId">): { href: string; label: string } | null {
  // Calibration also runs on the tenant, but it is a meta-run with no page of its own.
  if (r.workflow === "calibration") return null;
  switch (r.subjectType) {
    case "report-job":
      return { href: "/app/reports", label: "Open reports" };
    case "prospect":
      return { href: `/app/deals?prospect=${r.subjectId.split(":")[0]}`, label: "Open the deal" };
    case "compliance":
    case "portfolio":
      return { href: "/app/compliance", label: "Open compliance" };
    case "tenant":
      return { href: "/app/brain", label: "Open company brain" };
    // Equity bridge and operating review run on a company, the DDQ response on a questionnaire;
    // all three are started from the AI analysis page.
    case "company":
    case "ddq":
      return { href: "/app/analysis", label: "Open AI analysis" };
    default:
      return null;
  }
}

/** The human-readable text a run produced, if its output carries one. */
export function outputText(output: unknown): string | null {
  if (!output || typeof output !== "object") return typeof output === "string" ? output : null;
  const o = output as Record<string, unknown>;
  for (const k of ["memo", "answer", "rationale", "summary", "text"]) if (typeof o[k] === "string" && o[k]) return o[k] as string;
  return null;
}

/** The question or subject a run was asked about, in words. */
export function inputText(input: unknown): string | null {
  if (!input || typeof input !== "object") return null;
  const i = input as Record<string, unknown>;
  if (typeof i.question === "string") return i.question;
  if (typeof i.subject === "string") return `Subject: ${i.subject}${typeof i.asOf === "string" ? ` · as of ${i.asOf}` : ""}`;
  return null;
}
