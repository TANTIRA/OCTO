import type { Metadata } from "next";
import { AgentRunsView } from "@/components/live/agent-runs";
import { LivePage } from "@/components/views/live";

export const metadata: Metadata = { title: "Agent runs" };

export default function Page() {
  return (
    <LivePage eyebrow="Operate" title="Agent runs" description="Every AI draft in OCTO — what it was asked, which models ran, what the judge decided, and whether a person agreed.">
      <AgentRunsView />
    </LivePage>
  );
}
