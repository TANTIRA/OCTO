import type { Metadata, Viewport } from "next";
import { Geist, Geist_Mono, Newsreader } from "next/font/google";
import "./globals.css";

// Self-hosted at build time by next/font — CSP font-src stays 'self'.
const grotesk = Geist({ subsets: ["latin"], variable: "--font-geist" });
const figures = Geist_Mono({ subsets: ["latin"], variable: "--font-geist-mono" });
const editorial = Newsreader({
  subsets: ["latin"],
  style: ["normal", "italic"],
  variable: "--font-newsreader",
});

export const metadata: Metadata = {
  title: "OCTO by Mesta — One book of record for private markets",
  description:
    "Funds, deals, portfolio companies, and LPs normalized into a single governed investment ledger. One database, one system, one process.",
  openGraph: {
    title: "OCTO by Mesta — One book of record for private markets",
    description:
      "One governed investment ledger for funds, deals, portfolio companies, and LPs — self-hosted inside your perimeter.",
    type: "website",
  },
};

export const viewport: Viewport = { themeColor: "#000000" };

// Marks the document before first paint so [data-anim] elements start hidden
// only when script runs — no-JS visitors and reduced-motion users see content.
const motionFlag = "document.documentElement.classList.add('anim')";

export default function RootLayout({
  children,
}: Readonly<{ children: React.ReactNode }>) {
  return (
    <html
      lang="en"
      className={`${grotesk.variable} ${figures.variable} ${editorial.variable}`}
      suppressHydrationWarning
    >
      <head>
        <script dangerouslySetInnerHTML={{ __html: motionFlag }} />
      </head>
      <body
        className="bg-white text-neutral-900 antialiased dark:bg-neutral-950 dark:text-white"
        suppressHydrationWarning
      >
        {children}
      </body>
    </html>
  );
}
