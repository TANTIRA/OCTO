import type { Metadata } from "next";
import { ReportsView } from "@/components/live/reports";
import { LivePage } from "@/components/views/live";

export const metadata: Metadata = { title: "Reports" };

export default function Page() {
  return (
    <LivePage eyebrow="Insight" title="Reports" description="Compute performance from figures you sign for, then release it through an approval — the same numbers every time.">
      <ReportsView />
    </LivePage>
  );
}
