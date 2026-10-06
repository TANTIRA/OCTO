"use client";

/**
 * Frontend data layer (plan §34). Components never import demo arrays or call
 * fetch directly; they use these hooks. Each hook has a typed query key and a
 * stale-time policy by data class. Today the resolvers read the demo dataset
 * (labelled "Demo data" in the UI); swapping a resolver for `apiFetch` is the
 * only change needed when an endpoint exists.
 */

import { QueryClient, QueryClientProvider, useQuery } from "@tanstack/react-query";
import { useState } from "react";
import * as demo from "@/lib/demo";

/** Stale time by data class. */
export const STALE = {
  reference: 5 * 60_000, // funds, companies, rules
  positions: 60_000, // holdings, NAV, allocation
  workflow: 30_000, // alerts, recon, approvals, tasks
  feed: 60_000, // signals, activity
} as const;

export const keys = {
  funds: () => ["funds"] as const,
  fund: (id: string) => ["funds", id] as const,
  companies: () => ["companies"] as const,
  company: (id: string) => ["companies", id] as const,
  investments: (fundId?: string) => ["investments", fundId ?? "all"] as const,
  alerts: () => ["alerts"] as const,
  rules: () => ["rules"] as const,
  recon: () => ["recon"] as const,
  tasks: () => ["tasks"] as const,
  approvals: () => ["approvals"] as const,
  exceptions: () => ["exceptions"] as const,
  drafts: () => ["ai-drafts"] as const,
  signals: () => ["signals"] as const,
  sources: () => ["sources"] as const,
  metrics: () => ["metrics", "portfolio"] as const,
};

/** Demo resolver: resolves on the next tick with a short latency so loading states are real. */
function resolve<T>(value: T, ms = 220): Promise<T> {
  return new Promise((r) => setTimeout(() => r(value), ms));
}

export function QueryProvider({ children }: { children: React.ReactNode }) {
  const [client] = useState(
    () =>
      new QueryClient({
        defaultOptions: { queries: { refetchOnWindowFocus: false, retry: 1 } },
      }),
  );
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

export const useFunds = () => useQuery({ queryKey: keys.funds(), queryFn: () => resolve(demo.FUNDS), staleTime: STALE.reference });
export const useFund = (id: string) => useQuery({ queryKey: keys.fund(id), queryFn: () => resolve(demo.fundById(id) ?? null), staleTime: STALE.reference });
export const useCompanies = () => useQuery({ queryKey: keys.companies(), queryFn: () => resolve(demo.COMPANIES), staleTime: STALE.reference });
export const useCompany = (id: string) => useQuery({ queryKey: keys.company(id), queryFn: () => resolve(demo.companyById(id) ?? null), staleTime: STALE.reference });
export const useInvestments = (fundId?: string) =>
  useQuery({ queryKey: keys.investments(fundId), queryFn: () => resolve(fundId ? demo.investmentsForFund(fundId) : demo.INVESTMENTS), staleTime: STALE.positions });
export const useAlerts = () => useQuery({ queryKey: keys.alerts(), queryFn: () => resolve(demo.ALERTS), staleTime: STALE.workflow });
export const useRules = () => useQuery({ queryKey: keys.rules(), queryFn: () => resolve(demo.RULES), staleTime: STALE.reference });
export const useRecon = () => useQuery({ queryKey: keys.recon(), queryFn: () => resolve(demo.RECON), staleTime: STALE.workflow });
export const useTasks = () => useQuery({ queryKey: keys.tasks(), queryFn: () => resolve(demo.TASKS), staleTime: STALE.workflow });
export const useApprovals = () => useQuery({ queryKey: keys.approvals(), queryFn: () => resolve(demo.APPROVALS), staleTime: STALE.workflow });
export const useExceptions = () => useQuery({ queryKey: keys.exceptions(), queryFn: () => resolve(demo.EXCEPTIONS), staleTime: STALE.workflow });
export const useAiDrafts = () => useQuery({ queryKey: keys.drafts(), queryFn: () => resolve(demo.AI_DRAFTS), staleTime: STALE.workflow });
export const useSignals = () => useQuery({ queryKey: keys.signals(), queryFn: () => resolve(demo.SIGNALS), staleTime: STALE.feed });
export const useSources = () => useQuery({ queryKey: keys.sources(), queryFn: () => resolve(demo.SOURCES), staleTime: STALE.feed });
export const usePortfolioMetrics = () => useQuery({ queryKey: keys.metrics(), queryFn: () => resolve(demo.PORTFOLIO_METRICS), staleTime: STALE.positions });
