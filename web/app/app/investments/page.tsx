import { Suspense } from "react";
import type { Metadata } from "next";
import { InvestmentsView } from "@/components/views/investments";

export const metadata: Metadata = { title: "Investments" };

export default function InvestmentsPage() {
  return (
    <Suspense>
      <InvestmentsView />
    </Suspense>
  );
}
