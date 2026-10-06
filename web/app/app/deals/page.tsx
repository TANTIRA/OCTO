import type { Metadata } from "next";
import { DealsBoard } from "@/components/live/deals-board";
import { LivePage } from "@/components/views/live";

export const metadata: Metadata = { title: "Deals" };

export default function Page() {
  return (
    <LivePage eyebrow="Invest" title="Deals" description="The deal pipeline from first look to IC decision. Open a deal to see its next step, its history and every AI draft on it.">
      <DealsBoard />
    </LivePage>
  );
}
