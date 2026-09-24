"use client";

import { useState } from "react";
import { motion, AnimatePresence } from "motion/react";
import {
  Database,
  GitBranch,
  BarChart3,
  ShieldCheck,
  Sparkles,
  LifeBuoy,
  FileText,
  Menu,
  X,
  MessageCircle,
} from "lucide-react";

export function Navigation2() {
  const [activeMenu, setActiveMenu] = useState<string | null>(null);
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);

  const menuItems = {
    Platform: [
      {
        icon: Database,
        title: "Book of Record",
        description: "One append-only ledger for every position",
        href: "#",
      },
      {
        icon: GitBranch,
        title: "Deal Flow",
        description: "From sourcing to IC decision",
        href: "#",
      },
      {
        icon: BarChart3,
        title: "Analytics",
        description: "IRR, TVPI, and look-through exposure",
        href: "#",
      },
      {
        icon: ShieldCheck,
        title: "Governance",
        description: "Ontology, approvals, audit lineage",
        href: "#",
      },
    ],
    Resources: [
      {
        icon: Sparkles,
        title: "What's New",
        description: "Latest releases and methodology notes",
        href: "#",
      },
      {
        icon: LifeBuoy,
        title: "Help and Support",
        description: "Get help when you need it",
        href: "#",
      },
      {
        icon: FileText,
        title: "Documentation",
        description: "Guides, API reference, and the ontology",
        href: "#",
      },
      {
        icon: MessageCircle,
        title: "Contact",
        description: "Talk to the team",
        href: "#",
      },
    ],
  };

  return (
    <div className="min-h-[var(--rb-section-min-h,100vh)] w-full relative bg-white dark:bg-neutral-950">
      <div
        className="absolute inset-0 z-0 bg-linear-to-b from-violet-100/50 via-transparent to-transparent dark:from-transparent dark:via-transparent dark:to-transparent"
        style={{
          background:
            "radial-gradient(ellipse 80% 60% at 50% 0%, rgba(139, 92, 246, 0.15), transparent 70%), radial-gradient(ellipse 80% 60% at 50% 0%, rgba(139, 92, 246, 0.25), transparent 70%)",
        }}
      />
      <div
        className="absolute inset-0 z-0 dark:block hidden"
        style={{
          background:
            "radial-gradient(ellipse 80% 60% at 50% 0%, rgba(139, 92, 246, 0.25), transparent 70%), #0a0a0a",
        }}
      />

      <nav className="relative w-full px-4 sm:px-6 lg:px-8 py-6 sm:py-8 z-10">
        <div className="mx-auto w-full max-w-[1400px]">
          <motion.div
            className="relative mx-auto hidden lg:block"
            initial={{ opacity: 0, y: -20 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.5, ease: [0.4, 0, 0.2, 1] }}
            onMouseLeave={() => setActiveMenu(null)}
          >
            <div className="mx-auto w-fit rounded-3xl bg-white/40 backdrop-blur-2xl border border-neutral-200/50 shadow-xl dark:bg-neutral-950/20 dark:border-neutral-800/50 overflow-hidden">
              <div className="flex items-center justify-between gap-2 pl-6 pr-3 py-3">
                <a
                  href="#"
                  className="flex items-center text-xl font-medium text-tighter text-neutral-900 dark:text-white mr-6"
                >
                  Mesta-Asset
                </a>

                <div className="flex items-center gap-1">
                  <button
                    onMouseEnter={() => setActiveMenu("Platform")}
                    className="px-4 py-2 text-sm tracking-tight font-light text-neutral-600 dark:text-neutral-400 hover:text-neutral-900 dark:hover:text-white rounded-full"
                  >
                    Platform
                  </button>
                  <button
                    onMouseEnter={() => setActiveMenu("Resources")}
                    className="px-4 py-2 text-sm tracking-tight font-light text-neutral-600 dark:text-neutral-400 hover:text-neutral-900 dark:hover:text-white rounded-full"
                  >
                    Resources
                  </button>
                  <a
                    href="#"
                    className="px-4 py-2 text-sm tracking-tight font-light text-neutral-600 dark:text-neutral-400 hover:text-neutral-900 dark:hover:text-white rounded-full no-underline"
                    onMouseEnter={() => setActiveMenu(null)}
                  >
                    Contact
                  </a>
                </div>

                <div className="flex items-center gap-2 ml-6">
                  <a
                    href="#"
                    className="px-4 py-2 tracking-tight text-sm font-light text-neutral-600 dark:text-neutral-400 hover:text-neutral-900 dark:hover:text-white no-underline"
                    onMouseEnter={() => setActiveMenu(null)}
                  >
                    Log in
                  </a>
                  <a
                    href="#"
                    className="px-5 py-2 rounded-full bg-neutral-900 dark:bg-white text-white dark:text-neutral-900 text-sm font-light tracking-tight hover:bg-neutral-800 dark:hover:bg-neutral-200 no-underline"
                    onMouseEnter={() => setActiveMenu(null)}
                  >
                    Request access
                  </a>
                </div>
              </div>

              <AnimatePresence>
                {activeMenu && (
                  <motion.div
                    initial={{ height: 0, opacity: 0 }}
                    animate={{ height: "auto", opacity: 1 }}
                    exit={{ height: 0, opacity: 0 }}
                    transition={{ duration: 0.3, ease: [0.4, 0, 0.2, 1] }}
                    className="overflow-hidden"
                  >
                    <div className="p-2">
                      <div className="grid grid-cols-2 gap-3 w-[620px]">
                        {menuItems[activeMenu as keyof typeof menuItems].map(
                          (item, index) => {
                            const Icon = item.icon;
                            return (
                              <motion.a
                                key={item.title}
                                href={item.href}
                                initial={{ opacity: 0, y: 10 }}
                                animate={{ opacity: 1, y: 0 }}
                                transition={{
                                  duration: 0.2,
                                  delay: index * 0.05,
                                  ease: "easeOut",
                                }}
                                className="group flex items-start gap-3 rounded-2xl bg-white/20 backdrop-blur-2xl dark:bg-neutral-950/20 border border-neutral-300 dark:border-neutral-800/50 p-4 hover:border-neutral-300 dark:hover:border-neutral-700 hover:shadow-md transition-[border-color,box-shadow] duration-200"
                              >
                                <div className="shrink-0 rounded-lg bg-neutral-100 dark:bg-neutral-800 p-2">
                                  <Icon className="w-5 h-5 text-neutral-700 dark:text-neutral-300" />
                                </div>
                                <div className="flex-1 min-w-0">
                                  <h3 className="text-sm font-normal text-neutral-900 dark:text-white mb-0.5 group-hover:text-neutral-700 dark:group-hover:text-neutral-200 transition-colors">
                                    {item.title}
                                  </h3>
                                  <p className="text-xs text-neutral-600 dark:text-neutral-400 leading-snug">
                                    {item.description}
                                  </p>
                                </div>
                              </motion.a>
                            );
                          },
                        )}
                      </div>
                    </div>
                  </motion.div>
                )}
              </AnimatePresence>
            </div>
          </motion.div>

          <motion.div
            className="lg:hidden"
            initial={{ opacity: 0, y: -20 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.5, ease: [0.4, 0, 0.2, 1] }}
          >
            <div className="rounded-3xl bg-white/40 backdrop-blur-2xl border border-neutral-200/50 shadow-xl dark:bg-neutral-950/20 dark:border-neutral-800/50 overflow-hidden">
              <div className="flex items-center justify-between pl-4 pr-3 py-3">
                <a
                  href="#"
                  className="text-xl font-medium text-tighter text-neutral-900 dark:text-white"
                >
                  Mesta-Asset
                </a>

                <button
                  onClick={() => setMobileMenuOpen(!mobileMenuOpen)}
                  className="flex h-10 w-10 items-center justify-center rounded-full bg-neutral-900 dark:bg-white text-white dark:text-neutral-900"
                  aria-label={mobileMenuOpen ? "Close menu" : "Open menu"}
                  aria-expanded={mobileMenuOpen}
                >
                  {mobileMenuOpen ? (
                    <X className="h-5 w-5" />
                  ) : (
                    <Menu className="h-5 w-5" />
                  )}
                </button>
              </div>

              <AnimatePresence>
                {mobileMenuOpen && (
                  <motion.div
                    initial={{ height: 0, opacity: 0 }}
                    animate={{ height: "auto", opacity: 1 }}
                    exit={{ height: 0, opacity: 0 }}
                    transition={{ duration: 0.3, ease: [0.4, 0, 0.2, 1] }}
                    className="overflow-hidden"
                  >
                    <div className="px-4 pb-4 pt-2">
                      <div className="space-y-4">
                        <div className="space-y-1">
                          <a
                            href="#"
                            className="block py-2 px-2 text-sm font-medium text-neutral-900 dark:text-white no-underline"
                          >
                            Contact
                          </a>
                          <a
                            href="#"
                            className="block py-2 px-2 text-sm font-medium text-neutral-600 dark:text-neutral-400 no-underline"
                          >
                            Log in
                          </a>
                        </div>

                        <div>
                          <a
                            href="#"
                            className="block w-full text-center px-6 py-2.5 rounded-full bg-neutral-900 dark:bg-white text-white dark:text-neutral-900 text-sm font-medium no-underline"
                          >
                            Request access
                          </a>
                        </div>

                        <div className="pt-2 border-neutral-200 dark:border-neutral-800">
                          <h3 className="text-sm font-bold text-neutral-900 dark:text-white mb-2 px-2">
                            Platform
                          </h3>
                          <div className="space-y-2">
                            {menuItems.Platform.map((item) => {
                              const Icon = item.icon;
                              return (
                                <a
                                  key={item.title}
                                  href={item.href}
                                  className="flex items-start gap-3 rounded-xl bg-white/20 backdrop-blur-2xl dark:bg-neutral-950/20 border border-neutral-200/50 dark:border-neutral-800/50 p-3 no-underline"
                                >
                                  <div className="shrink-0 rounded-lg bg-neutral-100 dark:bg-neutral-800 p-2">
                                    <Icon className="w-4 h-4 text-neutral-700 dark:text-neutral-300" />
                                  </div>
                                  <div className="flex-1 min-w-0">
                                    <h4 className="text-sm font-semibold text-neutral-900 dark:text-white mb-0.5">
                                      {item.title}
                                    </h4>
                                    <p className="text-xs text-neutral-600 dark:text-neutral-400">
                                      {item.description}
                                    </p>
                                  </div>
                                </a>
                              );
                            })}
                          </div>
                        </div>

                        <div>
                          <h3 className="text-sm font-bold text-neutral-900 dark:text-white mb-2 px-2">
                            Resources
                          </h3>
                          <div className="space-y-2">
                            {menuItems.Resources.map((item) => {
                              const Icon = item.icon;
                              return (
                                <a
                                  key={item.title}
                                  href={item.href}
                                  className="flex items-start gap-3 rounded-xl bg-white/20 backdrop-blur-2xl dark:bg-neutral-950/20 border border-neutral-200/50 dark:border-neutral-800/50 p-3 no-underline"
                                >
                                  <div className="shrink-0 rounded-lg bg-neutral-100 dark:bg-neutral-800 p-2">
                                    <Icon className="w-4 h-4 text-neutral-700 dark:text-neutral-300" />
                                  </div>
                                  <div className="flex-1 min-w-0">
                                    <h4 className="text-sm font-semibold text-neutral-900 dark:text-white mb-0.5">
                                      {item.title}
                                    </h4>
                                    <p className="text-xs text-neutral-600 dark:text-neutral-400">
                                      {item.description}
                                    </p>
                                  </div>
                                </a>
                              );
                            })}
                          </div>
                        </div>
                      </div>
                    </div>
                  </motion.div>
                )}
              </AnimatePresence>
            </div>
          </motion.div>
        </div>
      </nav>
    </div>
  );
}

export default Navigation2;
