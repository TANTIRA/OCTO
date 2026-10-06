import { Suspense } from "react";
import type { Metadata } from "next";
import { DataSourcesView } from "@/components/views/data-sources";

export const metadata: Metadata = { title: "Data & Sources" };

export default function DataPage() {
  return (
    <Suspense>
      <DataSourcesView />
    </Suspense>
  );
}
