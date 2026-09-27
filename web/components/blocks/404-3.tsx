"use client";

import Link from "next/link";
import { motion } from "motion/react";

export default function NotFound3() {
  return (
    <section className="w-full min-h-screen flex items-start py-8 sm:py-12 px-4 sm:px-6 lg:px-8 bg-white dark:bg-neutral-950">
      <div className="max-w-[1400px] mx-auto w-full flex flex-col items-center text-center">
        <motion.div
          initial={{ opacity: 0, scale: 0.96 }}
          animate={{ opacity: 1, scale: 1 }}
          transition={{ duration: 0.8, ease: "easeOut" }}
          className="relative select-none px-10 sm:px-[120px] md:px-[140px] py-10 sm:py-20 -mt-8 sm:-mt-20 -mb-14 sm:-mb-28"
        >
          <span
            aria-hidden="true"
            className="absolute inset-0 flex items-center justify-center text-[110px] sm:text-[220px] md:text-[280px] lg:text-[340px] font-bold text-neutral-900 dark:text-white leading-none tracking-tight"
            style={{
              filter: "blur(14px)",
              WebkitMaskImage:
                "linear-gradient(to top, rgba(0,0,0,0) 25%, rgba(0,0,0,1) 60%, rgba(0,0,0,1) 82%, rgba(0,0,0,0) 100%)",
              maskImage:
                "linear-gradient(to top, rgba(0,0,0,0) 25%, rgba(0,0,0,1) 60%, rgba(0,0,0,1) 82%, rgba(0,0,0,0) 100%)",
            }}
          >
            404
          </span>
          <span
            className="relative flex items-center justify-center text-[110px] sm:text-[220px] md:text-[280px] lg:text-[340px] font-bold text-neutral-900 dark:text-white leading-none tracking-tight"
            style={{
              WebkitMaskImage:
                "linear-gradient(to top, rgba(0,0,0,1) 15%, rgba(0,0,0,1) 35%, rgba(0,0,0,0) 65%)",
              maskImage:
                "linear-gradient(to top, rgba(0,0,0,1) 15%, rgba(0,0,0,1) 35%, rgba(0,0,0,0) 65%)",
            }}
          >
            404
          </span>
        </motion.div>

        <motion.h1
          initial={{ opacity: 0, y: 12 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.5, delay: 0.25 }}
          className="mt-4 sm:mt-6 text-xl sm:text-2xl md:text-3xl font-bold text-neutral-900 dark:text-white tracking-tight"
        >
          The page you requested cannot be found.
        </motion.h1>

        <motion.p
          initial={{ opacity: 0, y: 12 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.5, delay: 0.35 }}
          className="mt-3 text-sm sm:text-base text-neutral-500 dark:text-neutral-400 leading-relaxed max-w-xs"
        >
          The link may be broken, or the page could have been taken down or
          renamed.
        </motion.p>

        <motion.div
          initial={{ opacity: 0, y: 12 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.5, delay: 0.45 }}
          whileHover={{ scale: 1.02 }}
          whileTap={{ scale: 0.98 }}
          className="mt-6 sm:mt-8"
        >
          <Link
            href="/"
            className="inline-block px-7 py-3 rounded-lg bg-neutral-900 dark:bg-white text-white dark:text-neutral-900 font-medium text-sm cursor-pointer hover:bg-neutral-800 dark:hover:bg-neutral-100 transition-colors duration-200"
          >
            Go back home
          </Link>
        </motion.div>
      </div>
    </section>
  );
}
