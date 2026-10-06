import { Suspense } from "react";
import type { Metadata } from "next";
import { WorkflowsView } from "@/components/views/workflows";

export const metadata: Metadata = { title: "Workflows" };

export default function WorkflowsPage() {
  return (
    <Suspense>
      <WorkflowsView />
    </Suspense>
  );
}
