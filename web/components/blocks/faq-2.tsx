"use client";

import { useState } from "react";
import { ArrowRight, Plus, Minus } from "lucide-react";
import { motion, AnimatePresence } from "motion/react";

export default function FAQ2() {
  const [openIndex, setOpenIndex] = useState<number | null>(null);

  const faqs = [
    {
      question: "Where does Mesta-Asset actually run?",
      answer:
        "On your infrastructure. The platform deploys as a self-hosted stack — Supabase PostgreSQL for the ledger, TypeDB for the ontology — so client, position, and LP data never leave your perimeter. There is no external SaaS dependency for core data.",
    },
    {
      question: "If the ledger is append-only, how do corrections work?",
      answer:
        "By supersession, never mutation. A correcting event references the original and carries a mandatory rationale, so every figure in a report can be traced back through its full history. Replay protection rejects duplicate writes from source systems.",
    },
    {
      question: "What does the AI actually decide?",
      answer:
        "It classifies documents, assesses claim support, and proposes screening outcomes — every decision carries model, provider, and request lineage. Confidential data is blocked from external APIs at the trust boundary, and high-impact actions still require human approval.",
    },
  ];

  const toggleFAQ = (index: number) => {
    setOpenIndex(openIndex === index ? null : index);
  };

  return (
    <section className="w-full min-h-[var(--rb-section-min-h,100vh)] flex items-start py-16 sm:py-20 px-4 sm:px-6 lg:px-8 bg-white dark:bg-neutral-950">
      <div className="max-w-[1400px] mx-auto w-full">
        <div className="grid grid-cols-1 lg:grid-cols-[1fr_2fr] gap-12 lg:gap-16 xl:gap-20 justify-start">
          <div className="flex flex-col space-y-6 lg:sticky lg:top-24 lg:self-start">
            <motion.h2
              initial={{ opacity: 0, y: 20 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ duration: 0.5 }}
              className="text-5xl font-medium text-neutral-900 dark:text-white leading-tight tracking-tighter"
            >
              Straight answers
            </motion.h2>

            <motion.div
              initial={{ opacity: 0, y: 20 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ duration: 0.5, delay: 0.1 }}
              className="flex flex-col sm:flex-row items-stretch gap-3 sm:gap-4"
            >
              <button className="px-6 sm:px-8 py-3 sm:py-3.5 rounded-full bg-neutral-900 dark:bg-white text-white dark:text-neutral-900 font-medium text-sm sm:text-base hover:bg-neutral-800 dark:hover:bg-neutral-200 transition-colors duration-200 whitespace-nowrap">
                Request access
              </button>
              <button className="group px-6 sm:px-8 py-3 sm:py-3.5 rounded-full border border-neutral-300 dark:border-neutral-700 text-neutral-900 dark:text-white font-medium text-sm sm:text-base hover:bg-neutral-100 dark:hover:bg-neutral-900 transition-colors duration-200 flex items-center justify-center gap-2 whitespace-nowrap">
                <span>Book a call</span>
                <ArrowRight className="w-4 h-4 transition-transform duration-200 group-hover:translate-x-1" />
              </button>
            </motion.div>
          </div>

          <div className="flex flex-col space-y-7">
            {faqs.map((faq, index) => (
              <motion.div
                key={index}
                initial={{ opacity: 0, y: 20 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ duration: 0.5, delay: 0.1 + index * 0.05 }}
                className="flex flex-col"
              >
                <div className="flex items-start gap-3">
                  <motion.button
                    onClick={() => toggleFAQ(index)}
                    aria-expanded={openIndex === index}
                    className="flex-1 max-w-[85%] sm:max-w-[75%] text-left group cursor-pointer"
                    whileHover={{ scale: 1.02 }}
                    whileTap={{ scale: 0.98 }}
                  >
                    <div
                      className={`px-4 sm:px-5 py-3 sm:py-3.5 rounded-full transition-all duration-200 ${
                        openIndex === index
                          ? "bg-violet-500/10 border border-violet-500/30"
                          : "bg-neutral-200 dark:bg-neutral-800 border border-transparent hover:bg-neutral-300 dark:hover:bg-neutral-700"
                      }`}
                    >
                      <p
                        className={`text-sm sm:text-base leading-relaxed transition-colors duration-200 ${
                          openIndex === index
                            ? "text-violet-600 dark:text-violet-400"
                            : "text-neutral-900 dark:text-white"
                        }`}
                      >
                        {faq.question}
                      </p>
                    </div>
                  </motion.button>

                  <button
                    onClick={() => toggleFAQ(index)}
                    aria-expanded={openIndex === index}
                    aria-label={
                      openIndex === index ? "Collapse answer" : "Expand answer"
                    }
                    className={`shrink-0 w-6 h-6 sm:w-7 sm:h-7 rounded-full border flex items-center justify-center transition-colors duration-200 mt-3 ${
                      openIndex === index
                        ? "border-violet-500/50 hover:border-violet-400"
                        : "border-neutral-400 dark:border-neutral-600 hover:border-neutral-500 dark:hover:border-neutral-400"
                    }`}
                  >
                    {openIndex === index ? (
                      <Minus
                        className={`w-3 h-3 sm:w-3.5 sm:h-3.5 ${
                          openIndex === index
                            ? "text-violet-600 dark:text-violet-400"
                            : "text-neutral-900 dark:text-white"
                        }`}
                      />
                    ) : (
                      <Plus className="w-3 h-3 sm:w-3.5 sm:h-3.5 text-neutral-600 dark:text-neutral-400" />
                    )}
                  </button>
                </div>

                <AnimatePresence initial={false}>
                  {openIndex === index && (
                    <motion.div
                      initial={{ height: 0, opacity: 0 }}
                      animate={{ height: "auto", opacity: 1 }}
                      exit={{ height: 0, opacity: 0 }}
                      transition={{
                        height: { duration: 0.4, ease: [0.4, 0, 0.2, 1] },
                        opacity: { duration: 0.3, ease: "easeInOut" },
                      }}
                      className="overflow-hidden"
                    >
                      <motion.div
                        initial={{ scale: 0.2, y: -10 }}
                        animate={{ scale: 1, y: 0 }}
                        exit={{ scale: 0.5, y: -10 }}
                        transition={{ duration: 0.3, ease: [0.4, 0, 0.2, 1] }}
                        className="self-end max-w-[85%] sm:max-w-[75%] mt-4 ml-auto"
                      >
                        <div className="px-4 sm:px-5 py-3 sm:py-3.5 rounded-[20px] bg-violet-600">
                          <p className="text-sm sm:text-base text-white leading-relaxed">
                            {faq.answer}
                          </p>
                        </div>
                      </motion.div>
                    </motion.div>
                  )}
                </AnimatePresence>
              </motion.div>
            ))}
          </div>
        </div>
      </div>
    </section>
  );
}
