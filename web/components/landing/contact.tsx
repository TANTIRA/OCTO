"use client";

import { ArrowRight, ChevronDown } from "lucide-react";
import { useState, type FormEvent } from "react";
import { Reveal } from "./motion";

// Mirrors ContactController's constraints so the browser rejects what the API would.
const FIELDS = [
  { name: "email", label: "Work email", type: "email", auto: "email", max: 320, required: true, wide: true },
  { name: "firstName", label: "First name", type: "text", auto: "given-name", max: 120, required: true },
  { name: "lastName", label: "Last name", type: "text", auto: "family-name", max: 120, required: true },
  { name: "firm", label: "Firm", type: "text", auto: "organization", max: 200, required: true },
  { name: "role", label: "Role", type: "text", auto: "organization-title", max: 120, required: true },
  { name: "phone", label: "Phone (optional)", type: "tel", auto: "tel", max: 60, required: false },
] as const;

const AUM_BANDS = ["Under $250M", "$250M – $1B", "$1B – $10B", "$10B+"];

const field =
  "w-full rounded-2xl border border-white/10 bg-white/5 px-4 py-3.5 text-sm text-white placeholder:text-white/35 transition-colors focus-visible:border-white/40 focus-visible:outline-none";
const label = "mb-2 block text-xs font-medium text-white/60";

type Status = "idle" | "pending" | "sent" | "limited" | "error";

export function Contact() {
  const [status, setStatus] = useState<Status>("idle");

  async function onSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const form = e.currentTarget;
    const data = new FormData(form);
    const text = (key: string) => String(data.get(key) ?? "").trim();
    setStatus("pending");
    try {
      const res = await fetch("/api/v1/contact", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({
          email: text("email"),
          firstName: text("firstName"),
          lastName: text("lastName"),
          firm: text("firm"),
          role: text("role"),
          aumBand: text("aumBand"),
          phone: text("phone") || undefined,
          message: text("message") || undefined,
          website: text("website") || undefined,
        }),
      });
      if (res.ok) form.reset();
      setStatus(res.ok ? "sent" : res.status === 429 ? "limited" : "error");
    } catch {
      setStatus("error");
    }
  }

  return (
    <section id="contact" className="scroll-mt-16 bg-black px-4 py-24 text-white sm:px-6 sm:py-32 lg:px-8">
      <Reveal className="mx-auto grid max-w-[1320px] gap-14 lg:grid-cols-[0.9fr_1.1fr] lg:gap-20">
        <div>
          <h2 data-anim className="font-display text-5xl font-semibold leading-[0.98] tracking-[-0.045em] sm:text-7xl">
            See your numbers in one place.
          </h2>
          <p data-anim className="mt-6 max-w-md font-editorial text-xl leading-relaxed text-white/65">
            Talk to a specialist about bringing your funds, deals, and
            reporting onto one shared record — running on your own
            infrastructure.
          </p>
          <ol data-anim className="mt-12 max-w-md space-y-4 text-sm text-white/70">
            {["A working session on your fund structure and data sources", "A walkthrough of the ledger, screening, and LP reporting", "A pilot plan with a realistic rollout"].map(
              (step, i) => (
                <li key={step} className="flex gap-4 border-t border-white/10 pt-4">
                  <span className="font-figures text-white/40">0{i + 1}</span>
                  {step}
                </li>
              ),
            )}
          </ol>
        </div>

        <form
          data-anim
          onSubmit={onSubmit}
          className="rounded-[28px] border border-white/10 bg-neutral-950 p-6 sm:p-10"
        >
          <div className="grid gap-5 sm:grid-cols-2">
            {FIELDS.map((f) => (
              <div key={f.name} className={"wide" in f ? "sm:col-span-2" : undefined}>
                <label htmlFor={`contact-${f.name}`} className={label}>
                  {f.label}
                </label>
                <input
                  id={`contact-${f.name}`}
                  name={f.name}
                  type={f.type}
                  autoComplete={f.auto}
                  maxLength={f.max}
                  required={f.required}
                  className={field}
                />
              </div>
            ))}
            <div className="sm:col-span-2">
              <label htmlFor="contact-aum" className={label}>
                Assets under management
              </label>
              <div className="relative">
                <select
                  id="contact-aum"
                  name="aumBand"
                  defaultValue=""
                  required
                  className={`${field} cursor-pointer appearance-none pr-10 invalid:text-white/35`}
                >
                  <option value="" disabled>
                    Select a range
                  </option>
                  {AUM_BANDS.map((b) => (
                    <option key={b}>{b}</option>
                  ))}
                </select>
                <ChevronDown className="pointer-events-none absolute right-4 top-1/2 h-4 w-4 -translate-y-1/2 text-white/40" />
              </div>
            </div>
            <div className="sm:col-span-2">
              <label htmlFor="contact-message" className={label}>
                Anything else? (optional)
              </label>
              <textarea
                id="contact-message"
                name="message"
                rows={4}
                maxLength={2000}
                placeholder="Fund structure, current stack, timeline."
                className={`${field} resize-none`}
              />
            </div>
          </div>

          {/* Honeypot — hidden from humans; bots that fill it get a silent 202. */}
          <div className="hidden" aria-hidden="true">
            <label htmlFor="contact-website">Website</label>
            <input id="contact-website" name="website" type="text" tabIndex={-1} autoComplete="off" />
          </div>

          <p className="mt-6 text-xs leading-relaxed text-white/40">
            We only use your details to contact you about OCTO.
          </p>
          <p role="status" aria-live="polite" className="mt-3 min-h-5 text-sm">
            {status === "sent" && <span className="text-emerald-400">Thanks — a specialist will reach out shortly.</span>}
            {status === "limited" && <span className="text-amber-300">Too many requests. Please try again in a minute.</span>}
            {status === "error" && <span className="text-red-400">Something went wrong sending that. Please try again.</span>}
          </p>

          <button
            type="submit"
            disabled={status === "pending"}
            className="group mt-4 inline-flex w-full cursor-pointer items-center justify-center gap-2 rounded-full bg-white px-6 py-4 text-sm font-medium text-black transition-colors hover:bg-neutral-200 focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-white disabled:cursor-wait disabled:opacity-60"
          >
            {status === "pending" ? "Sending…" : "Request access"}
            <ArrowRight className="h-4 w-4 transition-transform group-hover:translate-x-0.5" />
          </button>
        </form>
      </Reveal>
    </section>
  );
}
