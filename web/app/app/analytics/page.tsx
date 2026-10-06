import { Suspense } from "react";
import type { Metadata } from "next";
import { AnalyticsView } from "@/components/views/analytics";

export const metadata: Metadata = { title: "Analytics" };

export default function AnalyticsPage() {
  return (
    <Suspense>
      <AnalyticsView />
    </Suspense>
  );
}
