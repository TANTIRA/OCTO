"use client";

import { motion, useReducedMotion, type Variants } from "motion/react";
import { ArrowRight, Github, Linkedin } from "lucide-react";

const focusRing =
  "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-neutral-900 focus-visible:ring-offset-2 focus-visible:ring-offset-white dark:focus-visible:ring-white dark:focus-visible:ring-offset-neutral-950";

const navGroups = [
  { title: "Platform", links: ["Book of Record", "Deal Flow", "Analytics", "Changelog"] },
  { title: "Build", links: ["Docs", "API reference", "Ontology", "Status"] },
  { title: "Company", links: ["About", "Careers", "Customers", "Contact"] },
  { title: "Legal", links: ["Privacy", "Terms", "Security", "Cookies"] },
];

const dispatches = ["Weekly signal", "Release radar", "Methodology memo"];

const vitals = [
  { label: "Status", value: "All systems operational", live: true },
  { label: "Uptime", value: "99.98%, last 90 days" },
  { label: "Latest release", value: "v0.9. Attribution engine" },
  { label: "Next dispatch", value: "Friday, 06:00 UTC" },
];

const container: Variants = {
  hidden: {},
  visible: { transition: { staggerChildren: 0.12, delayChildren: 0.05 } },
};

const item: Variants = {
  hidden: { opacity: 0, y: 24 },
  visible: {
    opacity: 1,
    y: 0,
    transition: { duration: 0.7, ease: [0.22, 1, 0.36, 1] },
  },
};

export default function Footer12() {
  const reduce = useReducedMotion();

  return (
    <footer className="w-full bg-white px-4 py-16 dark:bg-neutral-950 sm:px-6 sm:py-20 lg:px-8 lg:py-24">
      <motion.div
        variants={container}
        initial={reduce ? false : "hidden"}
        whileInView="visible"
        viewport={{ once: true, margin: "-80px" }}
        className="mx-auto w-full max-w-[1400px]"
      >
        <motion.div
          variants={item}
          className="relative overflow-hidden rounded-3xl bg-neutral-900 p-7 ring-1 ring-neutral-950/10 dark:ring-white/10 sm:p-10 lg:p-14"
        >
          <div
            aria-hidden="true"
            className="pointer-events-none absolute -right-24 -top-24 h-72 w-72 rounded-full bg-white/[0.04] blur-3xl"
          />
          <div className="relative grid grid-cols-1 gap-10 lg:grid-cols-[1.2fr_1fr] lg:gap-16">
            <div>
              <div className="flex items-center gap-3">
                <span className="flex h-10 w-10 items-center justify-center rounded-xl bg-white text-base font-semibold text-neutral-900">
                  M
                </span>
                <span className="text-sm font-medium text-neutral-400">
                  The Ledger Dispatch
                </span>
              </div>
              <h2 className="mt-8 max-w-xl text-3xl font-medium leading-[1.12] tracking-tight text-white sm:text-4xl">
                The briefing for teams that run on one book of record.
              </h2>
              <p className="mt-4 max-w-md text-sm leading-relaxed text-neutral-400 sm:text-base">
                Release notes, methodology updates, and the numbers that moved.
                One email, every other Friday.
              </p>
              <form
                className="mt-8 flex w-full max-w-md flex-col gap-2 rounded-2xl border border-white/10 bg-white/[0.04] p-2 transition-colors duration-200 focus-within:border-white/30 sm:flex-row sm:items-center sm:rounded-full sm:p-1.5 sm:pl-5"
                onSubmit={(e) => e.preventDefault()}
              >
                <label htmlFor="footer-12-email" className="sr-only">
                  Work email
                </label>
                <input
                  id="footer-12-email"
                  type="email"
                  placeholder="work@email.com"
                  className="min-w-0 flex-1 rounded-xl bg-transparent px-3 py-2 text-sm text-white placeholder:text-neutral-500 focus-visible:outline-none sm:px-0 sm:py-0"
                />
                <button
                  type="submit"
                  className="inline-flex cursor-pointer items-center justify-center gap-2 rounded-xl bg-white px-5 py-2.5 text-sm font-medium text-neutral-900 transition-colors duration-200 hover:bg-neutral-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-white focus-visible:ring-offset-2 focus-visible:ring-offset-neutral-900 sm:rounded-full"
                >
                  Subscribe
                  <ArrowRight className="h-4 w-4" />
                </button>
              </form>
              <div className="mt-5 flex flex-wrap gap-2">
                {dispatches.map((chip) => (
                  <span
                    key={chip}
                    className="rounded-full border border-white/10 px-3 py-1 text-xs font-medium text-neutral-400"
                  >
                    {chip}
                  </span>
                ))}
              </div>
            </div>

            <div className="flex flex-col justify-center border-t border-white/10 pt-10 lg:border-l lg:border-t-0 lg:pl-14 lg:pt-0">
              <dl className="divide-y divide-white/10">
                {vitals.map((vital) => (
                  <div
                    key={vital.label}
                    className="flex items-center justify-between gap-6 py-4 first:pt-0 last:pb-0"
                  >
                    <dt className="text-xs font-medium uppercase tracking-[0.16em] text-neutral-500">
                      {vital.label}
                    </dt>
                    <dd className="flex items-center gap-2.5 text-right text-sm font-medium text-white">
                      {vital.live && (
                        <span className="relative flex h-2.5 w-2.5">
                          {!reduce && (
                            <motion.span
                              aria-hidden="true"
                              className="absolute inline-flex h-full w-full rounded-full bg-emerald-400"
                              animate={{ scale: [1, 2.1], opacity: [0.5, 0] }}
                              transition={{
                                duration: 1.8,
                                repeat: Infinity,
                                ease: "easeOut",
                              }}
                            />
                          )}
                          <span className="relative inline-flex h-2.5 w-2.5 rounded-full bg-emerald-400" />
                        </span>
                      )}
                      {vital.value}
                    </dd>
                  </div>
                ))}
              </dl>
            </div>
          </div>
        </motion.div>

        <motion.div
          variants={item}
          className="mt-14 grid grid-cols-2 gap-x-8 gap-y-10 sm:grid-cols-4 lg:grid-cols-[1.35fr_1fr_1fr_1fr_1fr]"
        >
          <div className="col-span-2 sm:col-span-4 lg:col-span-1">
            <p className="text-lg font-semibold tracking-tight text-neutral-900 dark:text-white">
              Mesta-Asset
            </p>
            <p className="mt-3 max-w-[28ch] text-sm leading-relaxed text-neutral-500 dark:text-neutral-400">
              Private-markets infrastructure. One database, one system, one
              process.
            </p>
          </div>
          {navGroups.map((group) => (
            <nav key={group.title} aria-label={group.title} className="min-w-0">
              <h3 className="mb-5 text-xs font-medium uppercase tracking-[0.16em] text-neutral-500 dark:text-neutral-500">
                {group.title}
              </h3>
              <ul className="space-y-3">
                {group.links.map((link) => (
                  <li key={link}>
                    <a
                      href="#"
                      className={`rounded-sm text-sm text-neutral-600 transition-colors duration-200 hover:text-neutral-950 dark:text-neutral-400 dark:hover:text-white ${focusRing}`}
                    >
                      {link}
                    </a>
                  </li>
                ))}
              </ul>
            </nav>
          ))}
        </motion.div>

        <motion.div
          variants={item}
          className="mt-14 flex flex-col gap-4 border-t border-neutral-200 pt-6 dark:border-neutral-800 sm:flex-row sm:items-center sm:justify-between"
        >
          <p className="text-sm text-neutral-500 dark:text-neutral-500">
            © 2026 Mesta-Asset
          </p>
          <div className="flex items-center gap-1">
            <a
              href="#"
              aria-label="X profile"
              className="flex h-9 w-9 items-center justify-center rounded-full text-neutral-500 transition-colors duration-200 hover:bg-neutral-100 hover:text-neutral-900 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-neutral-900 dark:text-neutral-500 dark:hover:bg-neutral-900 dark:hover:text-white dark:focus-visible:ring-white"
            >
              <svg viewBox="0 0 24 24" className="h-4 w-4 fill-current">
                <path d="M18.244 2.25h3.308l-7.227 8.26 8.502 11.24H16.17l-5.214-6.817L4.99 21.75H1.68l7.73-8.835L1.254 2.25H8.08l4.713 6.231zm-1.161 17.52h1.833L7.084 4.126H5.117z" />
              </svg>
            </a>
            <a
              href="#"
              aria-label="GitHub"
              className="flex h-9 w-9 items-center justify-center rounded-full text-neutral-500 transition-colors duration-200 hover:bg-neutral-100 hover:text-neutral-900 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-neutral-900 dark:text-neutral-500 dark:hover:bg-neutral-900 dark:hover:text-white dark:focus-visible:ring-white"
            >
              <Github className="h-[18px] w-[18px]" />
            </a>
            <a
              href="#"
              aria-label="LinkedIn"
              className="flex h-9 w-9 items-center justify-center rounded-full text-neutral-500 transition-colors duration-200 hover:bg-neutral-100 hover:text-neutral-900 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-neutral-900 dark:text-neutral-500 dark:hover:bg-neutral-900 dark:hover:text-white dark:focus-visible:ring-white"
            >
              <Linkedin className="h-[18px] w-[18px]" />
            </a>
          </div>
        </motion.div>
      </motion.div>
    </footer>
  );
}
