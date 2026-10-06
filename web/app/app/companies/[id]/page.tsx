import { Suspense } from "react";
import type { Metadata } from "next";
import { companyById } from "@/lib/demo";
import { CompanyDetail } from "@/components/views/company-detail";

export async function generateMetadata({ params }: { params: Promise<{ id: string }> }): Promise<Metadata> {
  const { id } = await params;
  return { title: companyById(id)?.name ?? "Company" };
}

export default async function CompanyPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return (
    <Suspense>
      <CompanyDetail id={id} />
    </Suspense>
  );
}
