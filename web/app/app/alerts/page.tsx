import { Suspense } from "react";
import type { Metadata } from "next";
import { AlertsView } from "@/components/views/alerts";

export const metadata: Metadata = { title: "Alerts" };

export default function AlertsPage() {
  return (
    <Suspense>
      <AlertsView />
    </Suspense>
  );
}
