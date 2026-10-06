import type { Metadata } from "next";
import { BrainView } from "@/components/live/brain";
import { LivePage } from "@/components/views/live";

export const metadata: Metadata = { title: "Company brain" };

export default function Page() {
  return (
    <LivePage eyebrow="Insight" title="Company brain" description="Ask about the pipeline and its history in plain language. Answers are judged; unsupported ones are refused, never guessed.">
      <BrainView />
    </LivePage>
  );
}
