import type { Metadata } from "next";
import { CompaniesView } from "@/components/views/companies";

export const metadata: Metadata = { title: "Companies" };

export default function CompaniesPage() {
  return <CompaniesView />;
}
