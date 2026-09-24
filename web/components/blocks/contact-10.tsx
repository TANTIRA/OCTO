"use client";

import { motion, useReducedMotion, type Variants } from "motion/react";
import { ChevronDown } from "lucide-react";

const benefits = [
  {
    title: "A strategic consultation",
    description:
      "Share your fund structure and constraints. We map your sources onto the ontology.",
  },
  {
    title: "Tailored walkthrough",
    description:
      "See the IBOR, screening, and LP reporting workflows that matter to your team.",
  },
  {
    title: "Enterprise readiness",
    description:
      "Discuss self-hosted deployment, data residency, and security with a specialist.",
  },
  {
    title: "Proof of value",
    description:
      "Leave with a pilot scope, reconciliation benchmarks, and a practical rollout path.",
  },
];

const fieldClass =
  "w-full rounded-xl border border-neutral-800 bg-neutral-900 px-4 py-3 text-sm text-white placeholder:text-neutral-500 transition-colors duration-200 focus-visible:border-neutral-500 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-white/15 dark:border-neutral-700 dark:bg-neutral-950 dark:focus-visible:border-neutral-500";
const labelClass = "mb-2 block text-sm font-medium text-neutral-300";

export default function Contact10() {
  const reduce = useReducedMotion();

  const container: Variants = {
    hidden: {},
    show: { transition: { staggerChildren: 0.08, delayChildren: 0.05 } },
  };
  const item: Variants = {
    hidden: { opacity: 0, y: reduce ? 0 : 16 },
    show: {
      opacity: 1,
      y: 0,
      transition: { duration: 0.6, ease: [0.22, 1, 0.36, 1] },
    },
  };

  return (
    <section className="w-full bg-white px-4 py-16 dark:bg-neutral-950 sm:px-6 sm:py-20 lg:px-8 lg:py-24">
      <div className="mx-auto grid w-full max-w-[1400px] items-start gap-10 lg:grid-cols-[minmax(0,0.9fr)_minmax(0,1.1fr)] lg:gap-16">
        <motion.div
          variants={container}
          initial="hidden"
          whileInView="show"
          viewport={{ once: true, margin: "-80px" }}
          className="flex flex-col"
        >
          <motion.h2
            variants={item}
            className="text-4xl font-medium leading-[1.05] tracking-tight text-neutral-900 dark:text-white sm:text-5xl md:text-6xl"
          >
            Turn positions
            <br />
            into conviction
          </motion.h2>
          <motion.p
            variants={item}
            className="mt-6 max-w-md text-base leading-relaxed text-neutral-600 dark:text-neutral-400 sm:text-lg"
          >
            Talk to a specialist about consolidating your funds, deals, and
            reporting onto one governed book of record — deployed inside your
            own perimeter.
          </motion.p>

          <div className="mt-12 grid gap-x-8 gap-y-6 sm:grid-cols-2 lg:grid-cols-1 xl:grid-cols-2">
            {benefits.map((benefit) => (
              <motion.div
                key={benefit.title}
                variants={item}
                className="flex gap-4"
              >
                <span className="mt-1.5 h-2.5 w-2.5 shrink-0 rounded-sm bg-violet-600 dark:bg-violet-400" />
                <div>
                  <h3 className="text-base font-medium text-neutral-900 dark:text-white">
                    {benefit.title}
                  </h3>
                  <p className="mt-1 text-sm leading-relaxed text-neutral-600 dark:text-neutral-400">
                    {benefit.description}
                  </p>
                </div>
              </motion.div>
            ))}
          </div>
        </motion.div>

        <motion.form
          initial={{ opacity: 0, y: reduce ? 0 : 24 }}
          whileInView={{ opacity: 1, y: 0 }}
          viewport={{ once: true, margin: "-80px" }}
          transition={{ duration: 0.7, delay: 0.1, ease: [0.22, 1, 0.36, 1] }}
          className="rounded-3xl border border-neutral-800 bg-neutral-950 p-6 shadow-2xl shadow-neutral-900/15 dark:border-neutral-800 dark:bg-neutral-900 dark:shadow-black/40 sm:p-8 lg:p-10"
          onSubmit={(e) => e.preventDefault()}
        >
          <div className="grid gap-5">
            <div>
              <label htmlFor="c10-email" className={labelClass}>
                Work email<span className="text-neutral-500">*</span>
              </label>
              <input
                id="c10-email"
                type="email"
                autoComplete="email"
                placeholder="you@fund.com"
                required
                className={fieldClass}
              />
            </div>

            <div className="grid gap-5 sm:grid-cols-2">
              <div>
                <label htmlFor="c10-first" className={labelClass}>
                  First name<span className="text-neutral-500">*</span>
                </label>
                <input
                  id="c10-first"
                  type="text"
                  autoComplete="given-name"
                  placeholder="Jane"
                  required
                  className={fieldClass}
                />
              </div>
              <div>
                <label htmlFor="c10-last" className={labelClass}>
                  Last name<span className="text-neutral-500">*</span>
                </label>
                <input
                  id="c10-last"
                  type="text"
                  autoComplete="family-name"
                  placeholder="Doe"
                  required
                  className={fieldClass}
                />
              </div>
            </div>

            <div>
              <label htmlFor="c10-company" className={labelClass}>
                Firm name<span className="text-neutral-500">*</span>
              </label>
              <input
                id="c10-company"
                type="text"
                autoComplete="organization"
                placeholder="Acme Capital"
                required
                className={fieldClass}
              />
            </div>

            <div className="grid gap-5 sm:grid-cols-2">
              <div>
                <label htmlFor="c10-title" className={labelClass}>
                  Role<span className="text-neutral-500">*</span>
                </label>
                <input
                  id="c10-title"
                  type="text"
                  autoComplete="organization-title"
                  placeholder="Partner, COO, Head of Ops"
                  required
                  className={fieldClass}
                />
              </div>
              <div>
                <label htmlFor="c10-size" className={labelClass}>
                  Assets under management<span className="text-neutral-500">*</span>
                </label>
                <div className="relative">
                  <select
                    id="c10-size"
                    defaultValue=""
                    required
                    className={`${fieldClass} cursor-pointer appearance-none pr-10 invalid:text-neutral-500`}
                  >
                    <option value="" disabled>
                      Please select
                    </option>
                    <option>Under $250M</option>
                    <option>$250M – $1B</option>
                    <option>$1B – $10B</option>
                    <option>$10B+</option>
                  </select>
                  <ChevronDown className="pointer-events-none absolute right-4 top-1/2 h-4 w-4 -translate-y-1/2 text-neutral-500" />
                </div>
              </div>
            </div>

            <div>
              <label htmlFor="c10-phone" className={labelClass}>
                Phone number
              </label>
              <input
                id="c10-phone"
                type="tel"
                autoComplete="tel"
                placeholder="+1 555 000 1234"
                className={fieldClass}
              />
            </div>

            <div>
              <label htmlFor="c10-message" className={labelClass}>
                Anything else you&apos;d like to share?
              </label>
              <textarea
                id="c10-message"
                rows={5}
                placeholder="Tell us about your fund structure, current stack, or timeline."
                className={`${fieldClass} resize-none`}
              />
            </div>
          </div>

          <p className="mt-5 text-xs leading-relaxed text-neutral-500">
            We only use your details to contact you about our products and
            services. By submitting, you agree to our{" "}
            <a
              href="#"
              className="cursor-pointer underline underline-offset-2 transition-colors hover:text-neutral-300 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-white/40"
            >
              Terms
            </a>{" "}
            and{" "}
            <a
              href="#"
              className="cursor-pointer underline underline-offset-2 transition-colors hover:text-neutral-300 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-white/40"
            >
              Privacy Policy
            </a>
            .
          </p>

          <motion.button
            type="submit"
            whileHover={reduce ? undefined : { y: -1 }}
            whileTap={{ scale: 0.99 }}
            className="mt-6 w-full cursor-pointer rounded-full bg-white px-6 py-3.5 text-sm font-medium text-black transition-colors hover:bg-neutral-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-white focus-visible:ring-offset-2 focus-visible:ring-offset-neutral-950 dark:focus-visible:ring-offset-neutral-900"
          >
            Submit
          </motion.button>
        </motion.form>
      </div>
    </section>
  );
}
