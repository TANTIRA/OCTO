import type { Metadata } from "next";
import AnalysisPanel from "@/components/analysis-panel";
import { LivePage } from "@/components/views/live";

export const metadata: Metadata = { title: "AI analysis" };

export default function Page() {
  return (
    <LivePage eyebrow="Insight" title="AI analysis" description="Narrated analysis from figures you supply — an equity bridge, a DDQ response or an operating review. Every narrative is judged and recorded in Agent runs.">
      <AnalysisPanel />
    </LivePage>
  );
}
