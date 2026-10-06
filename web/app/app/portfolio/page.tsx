import type { Metadata } from "next";
import { PortfolioView } from "@/components/views/portfolio";

export const metadata: Metadata = { title: "Portfolio" };

export default function PortfolioPage() {
  return <PortfolioView />;
}
