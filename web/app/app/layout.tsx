import type { Metadata } from "next";
import { Inter, IBM_Plex_Mono } from "next/font/google";
import { AppFrame } from "@/components/shell/app-frame";

// Loaded here, not in the root layout, so the landing page never preloads the
// dashboard's type. Self-hosted at build time by next/font — CSP font-src stays 'self'.
const inter = Inter({ subsets: ["latin"], variable: "--font-inter", display: "swap" });
const plexMono = IBM_Plex_Mono({ subsets: ["latin"], weight: ["400", "500"], variable: "--font-plex-mono", display: "swap" });

export const metadata: Metadata = {
  title: { default: "Control Center", template: "%s · OCTO" },
  robots: { index: false, follow: false },
};

export default function AppLayout({ children }: { children: React.ReactNode }) {
  return <AppFrame className={`${inter.variable} ${plexMono.variable}`}>{children}</AppFrame>;
}
