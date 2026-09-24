"use client";

import { ArrowRight, ArrowUp } from "lucide-react";
import { motion } from "motion/react";
import { useState } from "react";

export function Hero15() {
  const [query, setQuery] = useState("");
  const [isButtonHovered, setIsButtonHovered] = useState(false);

  return (
    <section className="w-full min-h-[var(--rb-section-min-h,100vh)] flex items-center py-12 px-4 sm:px-6 lg:px-8 bg-white dark:bg-neutral-950">
      <div className="max-w-[1400px] mx-auto w-full">
        <div className="flex flex-col items-center text-center">
          <motion.div
            initial={{ opacity: 0, y: 16 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.5, delay: 0.1 }}
          >
            <a
              href="#"
              className="inline-block whitespace-nowrap px-5 py-2.5 rounded-full bg-violet-500/10 border border-violet-500/20 text-[11px] sm:text-xs font-medium tracking-[0.14em] uppercase text-violet-700 dark:text-violet-300 cursor-pointer hover:bg-violet-500/20 transition-colors duration-200"
            >
              Private beta: onboarding now open
            </a>
          </motion.div>

          <motion.h1
            initial={{ opacity: 0, y: 20 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.7, delay: 0.2 }}
            className="mt-8 sm:mt-14 text-5xl sm:text-7xl md:text-8xl lg:text-[7rem] xl:text-[8.5rem] font-light italic text-neutral-800 dark:text-white leading-none tracking-[-0.02em]"
            style={{ fontFamily: "Georgia, 'Times New Roman', Times, serif" }}
          >
            One book of record
          </motion.h1>

          <motion.div
            initial={{ opacity: 0, y: 16 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.5, delay: 0.4 }}
            className="mt-6 sm:mt-12 max-w-2xl"
          >
            <p className="text-sm sm:text-base md:text-lg font-medium text-neutral-800 dark:text-white leading-relaxed">
              Mesta-Asset is the investment platform for private markets.
            </p>
            <p className="text-sm sm:text-base md:text-lg font-normal text-neutral-500 dark:text-neutral-400 leading-relaxed mt-0.5">
              Funds, deals, portfolio companies, and LPs — normalized into a
              <br className="hidden sm:block" />
              single governed ledger, with analytics and LP reporting on top.
            </p>
          </motion.div>

          <motion.div
            initial={{ opacity: 0, y: 16 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.5, delay: 0.55 }}
            className="mt-6 sm:mt-10 w-full sm:w-auto"
          >
            <motion.a
              href="#"
              onMouseEnter={() => setIsButtonHovered(true)}
              onMouseLeave={() => setIsButtonHovered(false)}
              whileHover={{ scale: 1.03 }}
              whileTap={{ scale: 0.97 }}
              className="inline-flex items-center justify-center gap-3 w-full sm:w-auto px-8 sm:px-10 py-3.5 sm:py-4 rounded-full bg-neutral-900 dark:bg-white text-white dark:text-neutral-900 text-xs sm:text-sm font-medium tracking-[0.14em] uppercase cursor-pointer hover:bg-neutral-800 dark:hover:bg-neutral-200 transition-colors duration-200 shadow-sm"
            >
              Request access
              <motion.span
                animate={{ x: isButtonHovered ? 3 : 0 }}
                transition={{ duration: 0.2 }}
              >
                <ArrowRight className="w-4 h-4" />
              </motion.span>
            </motion.a>
          </motion.div>

          <motion.div
            initial={{ opacity: 0, y: 16 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.5, delay: 0.7 }}
            className="mt-10 sm:mt-20 w-full max-w-2xl"
          >
            <div className="flex items-center bg-neutral-200/60 dark:bg-neutral-800 rounded-full p-2.5">
              <input
                type="text"
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                placeholder="Ask across your book of record..."
                className="flex-1 bg-transparent text-neutral-900 dark:text-white placeholder-neutral-500 dark:placeholder-neutral-500 text-sm sm:text-base focus:outline-none border-0 min-w-0 px-4 sm:px-5 py-3"
              />
              <motion.button
                whileHover={{ scale: 1.05 }}
                whileTap={{ scale: 0.95 }}
                aria-label="Submit query"
                className="cursor-pointer shrink-0 w-12 h-12 rounded-full bg-neutral-900 dark:bg-white hover:bg-neutral-800 dark:hover:bg-neutral-200 transition-colors duration-200 flex items-center justify-center"
              >
                <ArrowUp className="w-5 h-5 text-white dark:text-neutral-900" />
              </motion.button>
            </div>
          </motion.div>

          <motion.p
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            transition={{ duration: 0.5, delay: 0.85 }}
            className="mt-6 sm:mt-8 text-sm sm:text-base text-neutral-400 dark:text-neutral-500 font-normal"
          >
            One database. One system. One process.
          </motion.p>
        </div>
      </div>
    </section>
  );
}

export default Hero15;
