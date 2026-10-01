"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import AuthGate from "@/components/auth-gate";
import NotFound from "@/app/not-found";
import { usePlatformAdminCheck } from "@/lib/use-tenants";

/**
 * Ops page — probes the API's actuator health endpoints through the
 * same-origin /ops-api rewrite (next.config.ts), so the internal API base
 * URL never reaches the page. Sessions live in the browser client, so the
 * gate runs client-side — a server-rendered page would ship probe results in
 * the HTML to anyone. AuthGate sends visitors without a session to /login;
 * signed-in users who are not platform admins (API `OCTO_PLATFORM_ADMINS`,
 * #312) get the 404 page, and the probes never run for them.
 */

type Probe = {
  label: string;
  path: string;
  status: "UP" | "DOWN" | "ERROR" | "PENDING";
  ms: number | null;
  detail: string;
};

const PROBES: { label: string; path: string }[] = [
  { label: "Health", path: "/actuator/health" },
  { label: "Readiness (incl. database)", path: "/actuator/health/readiness" },
  { label: "Liveness", path: "/actuator/health/liveness" },
];

async function probe(label: string, path: string): Promise<Probe> {
  const start = Date.now();
  try {
    const res = await fetch(`/ops-api${path}`, {
      cache: "no-store",
      signal: AbortSignal.timeout(4000),
    });
    const ms = Date.now() - start;
    const body = (await res.json().catch(() => ({}))) as { status?: string };
    return {
      label,
      path,
      status: res.ok && body.status === "UP" ? "UP" : "DOWN",
      ms,
      detail: body.status ?? `HTTP ${res.status}`,
    };
  } catch {
    return { label, path, status: "ERROR", ms: null, detail: "unreachable" };
  }
}

function AdminPanel() {
  const [probes, setProbes] = useState<Probe[]>(
    PROBES.map((p) => ({ ...p, status: "PENDING", ms: null, detail: "…" })),
  );

  useEffect(() => {
    let cancelled = false;
    Promise.all(PROBES.map((p) => probe(p.label, p.path))).then((results) => {
      if (!cancelled) setProbes(results);
    });
    return () => {
      cancelled = true;
    };
  }, []);

  const allUp = probes.every((p) => p.status === "UP");

  return (
    <div className="min-h-dvh w-full bg-white dark:bg-neutral-950">
      <div className="mx-auto flex min-h-dvh w-full max-w-3xl flex-col px-4 py-8 sm:px-6">
        <header className="flex items-center justify-between gap-3">
          <div>
            <h1 className="text-xl font-medium tracking-[-0.015em] text-neutral-900 dark:text-neutral-100">
              Ops
            </h1>
            <p className="mt-0.5 text-[13px] text-neutral-500 dark:text-neutral-400">
              Live status for the OCTO stack. Data fetched at request time.
            </p>
          </div>
          <span
            className={
              allUp
                ? "inline-flex items-center gap-1.5 rounded-full bg-emerald-50 px-2.5 py-1 text-xs font-medium text-emerald-700 dark:bg-emerald-500/10 dark:text-emerald-400"
                : "inline-flex items-center gap-1.5 rounded-full bg-red-50 px-2.5 py-1 text-xs font-medium text-red-700 dark:bg-red-500/10 dark:text-red-400"
            }
          >
            <span
              aria-hidden
              className={`h-1.5 w-1.5 rounded-full ${allUp ? "bg-emerald-500" : "bg-red-500"}`}
            />
            {allUp ? "All systems UP" : "Attention required"}
          </span>
        </header>

        <section className="mt-6 overflow-hidden rounded-[var(--rb-r-2xl,14px)] border border-neutral-200/70 bg-neutral-50 p-1 dark:border-neutral-800 dark:bg-neutral-900/50">
          <div className="flex h-10 items-center justify-between px-3">
            <h2 className="text-[13px] font-medium text-neutral-900 dark:text-neutral-100">
              API
            </h2>
          </div>
          <ul className="divide-y divide-neutral-100 rounded-[var(--rb-r-xl,12px)] border border-neutral-200/70 bg-white dark:divide-neutral-800 dark:border-neutral-800 dark:bg-neutral-950">
            {probes.map((p) => (
              <li key={p.path} className="flex items-center gap-3 px-4 py-3">
                <span
                  aria-hidden
                  className={`h-2 w-2 shrink-0 rounded-full ${
                    p.status === "UP"
                      ? "bg-emerald-500"
                      : p.status === "DOWN"
                        ? "bg-amber-500"
                        : p.status === "PENDING"
                          ? "animate-pulse bg-neutral-300 dark:bg-neutral-700"
                          : "bg-red-500"
                  }`}
                />
                <span className="min-w-0 flex-1">
                  <span className="block truncate text-[13px] font-medium text-neutral-900 dark:text-neutral-100">
                    {p.label}
                  </span>
                  <span className="block truncate font-mono text-[11px] text-neutral-500">
                    {p.path}
                  </span>
                </span>
                <span className="shrink-0 text-right">
                  <span
                    className={`block text-[13px] font-medium ${
                      p.status === "UP"
                        ? "text-emerald-600 dark:text-emerald-400"
                        : p.status === "PENDING"
                          ? "text-neutral-500"
                          : "text-red-600 dark:text-red-400"
                    }`}
                  >
                    {p.detail}
                  </span>
                  <span className="block text-[11px] tabular-nums text-neutral-500">
                    {p.ms !== null ? `${p.ms} ms` : "—"}
                  </span>
                </span>
              </li>
            ))}
          </ul>
        </section>

        <section className="mt-4 overflow-hidden rounded-[var(--rb-r-2xl,14px)] border border-neutral-200/70 bg-neutral-50 p-1 dark:border-neutral-800 dark:bg-neutral-900/50">
          <div className="flex h-10 items-center px-3">
            <h2 className="text-[13px] font-medium text-neutral-900 dark:text-neutral-100">
              Surfaces
            </h2>
          </div>
          <ul className="divide-y divide-neutral-100 rounded-[var(--rb-r-xl,12px)] border border-neutral-200/70 bg-white dark:divide-neutral-800 dark:border-neutral-800 dark:bg-neutral-950">
            {[
              { label: "App shell", href: "/app", note: "Portfolio ops" },
              { label: "Landing", href: "/", note: "Marketing site" },
              { label: "Sign in", href: "/login", note: "Auth surface" },
            ].map((l) => (
              <li key={l.href}>
                <Link
                  href={l.href}
                  className="flex items-center gap-3 px-4 py-3 text-left transition-colors hover:bg-neutral-50 dark:hover:bg-neutral-900"
                >
                  <span className="min-w-0 flex-1 text-[13px] font-medium text-neutral-900 dark:text-neutral-100">
                    {l.label}
                  </span>
                  <span className="text-xs text-neutral-500">{l.note}</span>
                </Link>
              </li>
            ))}
          </ul>
        </section>

        <p className="mt-auto pt-8 text-center text-xs text-neutral-400 dark:text-neutral-600">
          OCTO ops · refreshes on every load
        </p>
      </div>
    </div>
  );
}

function PlatformAdminGate() {
  const { admin, error, retry } = usePlatformAdminCheck();
  // A failed or timed-out check is not a "no": say so and offer a retry, so
  // an API outage — when this page matters most — never reads as a 404.
  if (error) {
    return (
      <div className="flex h-dvh w-full items-center justify-center bg-white px-4 dark:bg-neutral-950">
        <div role="alert" className="max-w-sm text-center">
          <p className="text-sm font-medium text-neutral-900 dark:text-neutral-100">
            Could not check access
          </p>
          <p className="mt-1 text-[13px] text-neutral-500 dark:text-neutral-400">
            {error}
          </p>
          <button
            type="button"
            onClick={retry}
            className="mt-4 inline-flex h-8 cursor-pointer items-center rounded-[var(--rb-r-md,8px)] border border-neutral-200 bg-white px-3 text-[13px] font-medium text-neutral-900 transition-colors hover:bg-neutral-50 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-neutral-900 dark:border-neutral-800 dark:bg-neutral-900 dark:text-neutral-100 dark:hover:bg-neutral-800 dark:focus-visible:outline-neutral-100"
          >
            Retry
          </button>
        </div>
      </div>
    );
  }
  if (admin === null) {
    return (
      <div className="flex h-dvh w-full items-center justify-center bg-white dark:bg-neutral-950">
        <div
          role="status"
          aria-label="Checking access"
          className="h-6 w-6 animate-spin rounded-full border-2 border-neutral-300 border-t-neutral-900 dark:border-neutral-700 dark:border-t-neutral-100"
        />
      </div>
    );
  }
  // Same surface as an unknown route: non-admins learn nothing about /admin.
  return admin ? <AdminPanel /> : <NotFound />;
}

export default function AdminPage() {
  return (
    <AuthGate>
      <PlatformAdminGate />
    </AuthGate>
  );
}
