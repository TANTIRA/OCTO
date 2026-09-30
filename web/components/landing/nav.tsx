"use client";

import { Menu, X } from "lucide-react";
import Image from "next/image";
import { useEffect, useState } from "react";
import { cn } from "@/lib/utils";
import wordmark from "@/public/mesta-wordmark-white.png";

const LINKS = [
  { label: "Platform", href: "#platform" },
  { label: "Ontology", href: "#ontology" },
  { label: "Security", href: "#security" },
  { label: "FAQ", href: "#faq" },
];

export function Nav() {
  const [scrolled, setScrolled] = useState(false);
  const [open, setOpen] = useState(false);

  useEffect(() => {
    const onScroll = () => setScrolled(window.scrollY > 24);
    onScroll();
    window.addEventListener("scroll", onScroll, { passive: true });
    return () => window.removeEventListener("scroll", onScroll);
  }, []);

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && setOpen(false);
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open]);

  return (
    <header
      className={cn(
        "fixed inset-x-0 top-0 z-50 text-white transition-[background-color,backdrop-filter,border-color] duration-300",
        scrolled || open
          ? "border-b border-white/10 bg-black/75 backdrop-blur-xl"
          : "border-b border-transparent",
      )}
    >
      <nav
        aria-label="Primary"
        className="mx-auto flex h-16 max-w-[1320px] items-center justify-between px-4 sm:px-6 lg:px-8"
      >
        <a href="#top" className="flex items-center gap-3" aria-label="Mesta OCTO — home">
          <Image src={wordmark} alt="" width={85} priority className="h-6 w-auto" />
          <span className="border-l border-white/20 pl-3 font-figures text-[11px] uppercase tracking-[0.18em] text-white/60">
            OCTO
          </span>
        </a>

        <ul className="hidden items-center gap-1 md:flex">
          {LINKS.map((l) => (
            <li key={l.href}>
              <a
                href={l.href}
                className="rounded-full px-4 py-2 text-sm font-medium text-white/70 transition-colors hover:text-white"
              >
                {l.label}
              </a>
            </li>
          ))}
        </ul>

        <div className="hidden items-center gap-2 md:flex">
          <a
            href="/login"
            className="rounded-full px-4 py-2 text-sm font-medium text-white/70 transition-colors hover:text-white"
          >
            Log in
          </a>
          <a
            href="#contact"
            className="rounded-full bg-white px-4 py-2 text-sm font-medium text-black transition-colors hover:bg-neutral-200"
          >
            Request access
          </a>
        </div>

        <button
          type="button"
          onClick={() => setOpen((v) => !v)}
          aria-expanded={open}
          aria-controls="mobile-menu"
          aria-label={open ? "Close menu" : "Open menu"}
          className="flex h-10 w-10 items-center justify-center rounded-full bg-white/10 md:hidden"
        >
          {open ? <X className="h-5 w-5" /> : <Menu className="h-5 w-5" />}
        </button>
      </nav>

      <div
        id="mobile-menu"
        hidden={!open}
        className="border-t border-white/10 px-4 pb-6 pt-2 md:hidden"
      >
        <ul className="flex flex-col">
          {[...LINKS, { label: "Log in", href: "/login" }].map((l) => (
            <li key={l.href}>
              <a
                href={l.href}
                onClick={() => setOpen(false)}
                className="block border-b border-white/10 py-4 text-lg font-medium"
              >
                {l.label}
              </a>
            </li>
          ))}
        </ul>
        <a
          href="#contact"
          onClick={() => setOpen(false)}
          className="mt-6 block rounded-full bg-white py-3 text-center text-sm font-medium text-black"
        >
          Request access
        </a>
      </div>
    </header>
  );
}
