import type { Metadata } from "next";
import { ComplianceView } from "@/components/live/compliance";
import { LivePage } from "@/components/views/live";

export const metadata: Metadata = { title: "Compliance" };

export default function Page() {
  return (
    <LivePage eyebrow="Operate" title="Compliance" description="Score a fund’s figures against the active limits as of a date. Breaches open review tasks; the AI drafts the rationale for the file.">
      <ComplianceView />
    </LivePage>
  );
}
