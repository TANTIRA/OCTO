"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";
import { useTenants } from "@/lib/use-tenants";

/** `detail` is a short descriptor (demo list only); the caller's role is shown once, in the account menu. */
export type Workspace = { slug: string; name: string; detail?: string };

const UPPER = /^(i{1,3}|iv|v|vi{0,3}|ix|x|spv|lp|gp|llc|ltd|pte)$/i;

/** "flagship-ii" → "Flagship II", "antero-spv" → "Antero SPV": the API only knows a workspace by its slug. */
export function workspaceName(slug: string): string {
  return slug
    .split(/[-_]+/)
    .filter(Boolean)
    .map((w) => (UPPER.test(w) ? w.toUpperCase() : w.charAt(0).toUpperCase() + w.slice(1)))
    .join(" ");
}
export type ApiStatus = "checking" | "connected" | "offline";

type WorkspaceContext = {
  workspaces: Workspace[];
  current: Workspace;
  setCurrent: (slug: string) => void;
  /** Whether the tenant list came from the API or the demo fallback. */
  source: "api" | "demo";
  apiStatus: ApiStatus;
  environment: "Local" | "Staging" | "Production";
};

/** Shown only when the API returns no memberships; always labelled as demo data in the UI. */
const DEMO: Workspace[] = [
  { slug: "flagship-ii", name: "OCTO Flagship Fund II", detail: "All vehicles" },
  { slug: "opportunities-i", name: "OCTO Opportunities I", detail: "Co-invest" },
  { slug: "antero-spv", name: "Antero SPV", detail: "Single deal" },
];

const Ctx = createContext<WorkspaceContext | null>(null);

function detectEnvironment(): WorkspaceContext["environment"] {
  if (typeof window === "undefined") return "Local";
  const h = window.location.hostname;
  if (h === "localhost" || h === "127.0.0.1" || h.endsWith(".localhost")) return "Local";
  if (h.includes("staging")) return "Staging";
  return "Production";
}

/**
 * Shell view of the workspace (tenant) selection. It reads and writes the one
 * selection held by `TenantProvider`, so the switcher, the command menu and
 * every live tenant-scoped panel agree on which workspace is open. Only when
 * the API returns no memberships does the shell fall back to a labelled demo
 * list, so the demo pages still render something coherent.
 */
export function WorkspaceProvider({ children }: { children: React.ReactNode }) {
  const { tenants, tenantId, setTenantId, loading, error } = useTenants();
  const [demoSlug, setDemoSlug] = useState(DEMO[0].slug);
  const [environment, setEnvironment] = useState<WorkspaceContext["environment"]>("Local");

  useEffect(() => setEnvironment(detectEnvironment()), []);

  const live = tenants.length > 0;
  const workspaces = useMemo<Workspace[]>(
    () => (live ? tenants.map((t) => ({ slug: t.slug, name: workspaceName(t.slug) })) : DEMO),
    [live, tenants],
  );

  const setCurrent = useCallback(
    (slug: string) => {
      if (!live) return setDemoSlug(slug);
      const tenant = tenants.find((t) => t.slug === slug);
      if (tenant) setTenantId(tenant.tenantId);
    },
    [live, tenants, setTenantId],
  );

  const value = useMemo<WorkspaceContext>(() => {
    const currentSlug = live ? tenants.find((t) => t.tenantId === tenantId)?.slug : demoSlug;
    return {
      workspaces,
      current: workspaces.find((w) => w.slug === currentSlug) ?? workspaces[0],
      setCurrent,
      source: live ? "api" : "demo",
      apiStatus: loading ? "checking" : error ? "offline" : "connected",
      environment,
    };
  }, [workspaces, live, tenants, tenantId, demoSlug, setCurrent, loading, error, environment]);

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useWorkspace(): WorkspaceContext {
  const v = useContext(Ctx);
  if (!v) throw new Error("useWorkspace must be used inside WorkspaceProvider");
  return v;
}
