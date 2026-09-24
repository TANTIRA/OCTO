"use client";

import { motion } from "motion/react";

const steps = [
  {
    n: 1,
    title: "Connect your sources",
    desc: "CRMs, custodians, market-data feeds, and documents flow in through governed adapters.",
  },
  {
    n: 2,
    title: "Normalize into the IBOR",
    desc: "Every commitment, transaction, and valuation lands in one append-only ledger.",
  },
  {
    n: 3,
    title: "Screen and decide",
    desc: "Configurable screening, DDQ support, and IC reporting — with full decision lineage.",
  },
  {
    n: 4,
    title: "Report with confidence",
    desc: "LP reports and look-through exposure derived from the same numbers, every time.",
  },
];

export default function HowItWorks5() {
  return (
    <section className="w-full min-h-[var(--rb-section-min-h,100vh)] flex items-center py-12 sm:py-16 px-4 sm:px-6 lg:px-8 bg-white dark:bg-neutral-950">
      <div className="max-w-[1400px] mx-auto w-full">
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-12 lg:gap-16 items-start">
          <motion.div
            initial={{ opacity: 0, y: 16 }}
            whileInView={{ opacity: 1, y: 0 }}
            viewport={{ once: true }}
            transition={{ duration: 0.6 }}
            className="flex flex-col gap-6"
          >
            <h2 className="text-4xl sm:text-5xl md:text-6xl font-medium text-neutral-900 dark:text-white tracking-tight">
              How it works
            </h2>
            <p className="text-base sm:text-lg text-neutral-600 dark:text-neutral-400 leading-relaxed max-w-md">
              From fragmented feeds to a governed book of record — no migration
              project that never ends, no numbers that disagree.
            </p>
            <motion.button
              whileHover={{ scale: 1.02 }}
              whileTap={{ scale: 0.98 }}
              className="self-start px-10 py-3.5 rounded-full bg-neutral-900 dark:bg-white text-white dark:text-neutral-900 text-xs tracking-[0.2em] uppercase font-bold transition-all cursor-pointer"
            >
              Request access
            </motion.button>
            <p className="text-sm text-neutral-500 dark:text-neutral-500">
              Deploying in a regulated environment?{" "}
              <a
                href="#"
                className="text-neutral-900 dark:text-white font-semibold hover:underline"
              >
                Talk to us
              </a>
              .
            </p>
          </motion.div>

          <div className="flex flex-col gap-10">
            {steps.map((s, i) => (
              <motion.div
                key={s.n}
                initial={{ opacity: 0, x: 20 }}
                whileInView={{ opacity: 1, x: 0 }}
                viewport={{ once: true }}
                transition={{ duration: 0.5, delay: 0.1 * i }}
                className="flex items-start gap-5"
              >
                <span className="w-12 h-12 rounded-full bg-neutral-900 dark:bg-white text-white dark:text-neutral-900 flex items-center justify-center text-lg font-bold shrink-0">
                  {s.n}
                </span>
                <div className="flex flex-col gap-1 pt-1">
                  <h3 className="text-lg sm:text-xl font-semibold text-neutral-900 dark:text-white tracking-tight">
                    {s.title}
                  </h3>
                  <p className="text-sm sm:text-base text-neutral-600 dark:text-neutral-400 leading-relaxed">
                    {s.desc}
                  </p>
                </div>
              </motion.div>
            ))}
          </div>
        </div>
      </div>
    </section>
  );
}
