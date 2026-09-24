import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Mesta-Asset — One book of record for private markets",
  description:
    "Funds, deals, portfolio companies, and LPs normalized into a single governed investment ledger. One database, one system, one process.",
};

export default function RootLayout({
  children,
}: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en">
      <body className="bg-white text-neutral-900 antialiased dark:bg-neutral-950 dark:text-white">
        {children}
      </body>
    </html>
  );
}
