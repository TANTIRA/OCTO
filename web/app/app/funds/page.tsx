import type { Metadata } from "next";
import { FundsView } from "@/components/views/funds";

export const metadata: Metadata = { title: "Funds" };

export default function FundsPage() {
  return <FundsView />;
}
