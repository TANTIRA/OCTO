import type { Metadata } from "next";
import { ReconciliationView } from "@/components/live/reconciliation";
import { LivePage } from "@/components/views/live";

export const metadata: Metadata = { title: "Reconciliation" };

export default function Page() {
  return (
    <LivePage eyebrow="Operate" title="Reconciliation" description="Check a custodian, administrator or bank against the IBOR. Matches confirm the book; every break becomes a review task.">
      <ReconciliationView />
    </LivePage>
  );
}
