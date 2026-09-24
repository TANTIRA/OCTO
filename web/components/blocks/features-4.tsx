"use client";

import { motion, AnimatePresence } from "motion/react";
import { useState, useEffect, useRef } from "react";
import { Database, BarChart3, ShieldCheck, Server } from "lucide-react";

interface Features4Props {
  autoPlay?: boolean;
  autoPlayDelay?: number;
}

export function Features4({
  autoPlay = true,
  autoPlayDelay = 5000,
}: Features4Props) {
  const [activeTab, setActiveTab] = useState(0);
  const intervalRef = useRef<NodeJS.Timeout | null>(null);

  const tabs = [
    {
      icon: Database,
      title: "Investment Book of Record",
      description:
        "One append-only ledger for commitments, transactions, cash flows, and valuations.",
      features: [
        "Positions and cash derived, never handwritten",
        "Corrections supersede — nothing is ever mutated",
        "Source-system idempotency on every write",
        "Full lineage from figure back to source document",
      ],
    },
    {
      icon: BarChart3,
      title: "Look-through & Analytics",
      description:
        "Entity and instrument hierarchies resolved into exposure and performance you can defend.",
      features: [
        "IRR, TVPI, MOIC, and DPI out of the box",
        "Look-through exposure across fund structures",
        "Brinson attribution per quantitative methodology",
        "Low-code models for bespoke metrics",
      ],
    },
    {
      icon: ShieldCheck,
      title: "Governed AI",
      description:
        "AI assists classification, screening, and diligence — under explicit governance.",
      features: [
        "Document classification and claim support",
        "Configurable deal screening criteria",
        "Every decision carries model lineage",
        "Human approval for high-impact actions",
      ],
    },
    {
      icon: Server,
      title: "Self-hosted by design",
      description:
        "Supabase PostgreSQL and TypeDB on your infrastructure — your data never leaves your perimeter.",
      features: [
        "No external SaaS dependency for core data",
        "Ontology versioned in Git like code",
        "Confidential data never reaches public APIs",
        "Deploy with your existing compliance posture",
      ],
    },
  ];

  useEffect(() => {
    if (!autoPlay) return;

    const startAutoPlay = () => {
      intervalRef.current = setInterval(() => {
        setActiveTab((prev) => (prev + 1) % tabs.length);
      }, autoPlayDelay);
    };

    startAutoPlay();

    return () => {
      if (intervalRef.current) {
        clearInterval(intervalRef.current);
      }
    };
  }, [autoPlay, autoPlayDelay, tabs.length]);

  const handleTabClick = (index: number) => {
    setActiveTab(index);

    if (autoPlay && intervalRef.current) {
      clearInterval(intervalRef.current);
      intervalRef.current = setInterval(() => {
        setActiveTab((prev) => (prev + 1) % tabs.length);
      }, autoPlayDelay);
    }
  };

  return (
    <section className="w-full py-16 sm:py-20 md:py-24 lg:py-32 px-4 sm:px-6 lg:px-8 bg-white dark:bg-neutral-950">
      <div className="max-w-[1400px] mx-auto">
        <div className="text-center mb-12 md:mb-16">
          <motion.h2
            initial={{ opacity: 0, y: 20 }}
            whileInView={{ opacity: 1, y: 0 }}
            viewport={{ once: true }}
            transition={{ duration: 0.5 }}
            className="text-3xl sm:text-4xl md:text-5xl lg:text-6xl font-medium tracking-tight text-neutral-900 dark:text-white mb-4"
          >
            Powerful features
          </motion.h2>

          <motion.p
            initial={{ opacity: 0, y: 20 }}
            whileInView={{ opacity: 1, y: 0 }}
            viewport={{ once: true }}
            transition={{ duration: 0.5, delay: 0.1 }}
            className="text-lg sm:text-xl text-neutral-600 dark:text-neutral-400 max-w-2xl mx-auto"
          >
            Everything you need to run private markets on one system
          </motion.p>
        </div>

        <div className="grid grid-cols-1 lg:grid-cols-12 gap-4">
          <div className="lg:col-span-4 flex flex-col justify-between gap-4">
            {tabs.map((tab, index) => {
              const Icon = tab.icon;
              const isActive = activeTab === index;

              return (
                <motion.button
                  key={index}
                  initial={{ opacity: 0, x: -20 }}
                  whileInView={{ opacity: 1, x: 0 }}
                  viewport={{ once: true }}
                  transition={{ duration: 0.4, delay: index * 0.05 }}
                  onClick={() => handleTabClick(index)}
                  className={`w-full text-left p-4 md:p-6 rounded-2xl transition-[border-color,background-color] duration-200 flex-1 flex items-start ${
                    isActive
                      ? " bg-neutral-100 dark:bg-neutral-800"
                      : " bg-neutral-50 dark:bg-neutral-900 hover:border-neutral-300 dark:hover:border-neutral-700"
                  }`}
                >
                  <div className="flex items-start gap-4">
                    <div
                      className={`shrink-0 w-10 h-10 rounded-lg flex items-center justify-center transition-colors duration-200 ${
                        isActive
                          ? "bg-neutral-900 dark:bg-white text-white dark:text-neutral-900"
                          : "bg-neutral-100 dark:bg-neutral-800 text-neutral-900 dark:text-white"
                      }`}
                    >
                      <Icon className="w-5 h-5" />
                    </div>

                    <div className="flex-1 min-w-0">
                      <h3
                        className={`text-base md:text-lg font-semibold mb-1 ${
                          isActive
                            ? "text-neutral-900 dark:text-white"
                            : "text-neutral-700 dark:text-neutral-300"
                        }`}
                      >
                        {tab.title}
                      </h3>
                      <p
                        className={`text-sm line-clamp-2 ${
                          isActive
                            ? "text-neutral-600 dark:text-neutral-400"
                            : "text-neutral-500 dark:text-neutral-500"
                        }`}
                      >
                        {tab.description}
                      </p>
                    </div>
                  </div>
                </motion.button>
              );
            })}
          </div>

          <div className="lg:col-span-8 flex">
            <AnimatePresence mode="wait">
              <motion.div
                key={activeTab}
                initial={{ opacity: 0, y: 20 }}
                animate={{ opacity: 1, y: 0 }}
                exit={{ opacity: 0, y: -20 }}
                transition={{ duration: 0.3 }}
                className="rounded-3xl border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-6 md:p-8 lg:p-10 flex-1"
              >
                <div className="mb-8">
                  <div className="inline-flex items-center justify-center w-16 h-16 rounded-2xl bg-violet-500/10 mb-6">
                    {(() => {
                      const Icon = tabs[activeTab].icon;
                      return (
                        <Icon className="w-8 h-8 text-violet-600 dark:text-violet-400" />
                      );
                    })()}
                  </div>

                  <h3 className="text-2xl md:text-3xl font-bold text-neutral-900 dark:text-white mb-3">
                    {tabs[activeTab].title}
                  </h3>

                  <p className="text-base md:text-lg text-neutral-600 dark:text-neutral-400 leading-relaxed">
                    {tabs[activeTab].description}
                  </p>
                </div>

                <div className="space-y-4">
                  {tabs[activeTab].features.map((feature, index) => (
                    <motion.div
                      key={index}
                      initial={{ opacity: 0, x: 20 }}
                      animate={{ opacity: 1, x: 0 }}
                      transition={{ duration: 0.3, delay: index * 0.1 }}
                      className="flex items-start gap-3 p-4 rounded-xl bg-neutral-50 dark:bg-neutral-800/50"
                    >
                      <div className="shrink-0 w-6 h-6 rounded-full bg-neutral-900 dark:bg-white flex items-center justify-center mt-0.5">
                        <svg
                          className="w-4 h-4 text-white dark:text-neutral-900"
                          fill="none"
                          viewBox="0 0 24 24"
                          stroke="currentColor"
                          strokeWidth={3}
                        >
                          <path
                            strokeLinecap="round"
                            strokeLinejoin="round"
                            d="M5 13l4 4L19 7"
                          />
                        </svg>
                      </div>
                      <span className="text-sm md:text-base text-neutral-700 dark:text-neutral-300 font-medium">
                        {feature}
                      </span>
                    </motion.div>
                  ))}
                </div>
              </motion.div>
            </AnimatePresence>
          </div>
        </div>
      </div>
    </section>
  );
}

export default Features4;
