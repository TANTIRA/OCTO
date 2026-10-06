import { Suspense } from "react";
import type { Metadata } from "next";
import { fundById } from "@/lib/demo";
import { FundDetail } from "@/components/views/fund-detail";

export async function generateMetadata({ params }: { params: Promise<{ id: string }> }): Promise<Metadata> {
  const { id } = await params;
  return { title: fundById(id)?.name ?? "Fund" };
}

export default async function FundPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return (
    <Suspense>
      <FundDetail id={id} />
    </Suspense>
  );
}
