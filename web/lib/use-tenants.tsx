"use client";

import { useEffect, useState } from "react";
import { myAccess, messageFor, type Tenant } from "@/lib/api";

/**
 * One shared read of `/api/v1/me/access` per mount. Every tenant-scoped
 * surface (pipeline, reports, recon, compliance, agent runs) renders its
 * picker from this — the API enforces the roles it returns, the UI only
 * narrows choices.
 */
export function useTenants(): {
  tenants: Tenant[];
  tenantId: string;
  setTenantId: (id: string) => void;
  loading: boolean;
  error: string | null;
} {
  const [tenants, setTenants] = useState<Tenant[]>([]);
  const [tenantId, setTenantId] = useState("");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    myAccess()
      .then((list) => {
        if (cancelled) return;
        setTenants(list);
        if (list.length === 1) setTenantId(list[0].tenantId);
        setLoading(false);
      })
      .catch((e) => {
        if (cancelled) return;
        setError(messageFor(e));
        setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  return { tenants, tenantId, setTenantId, loading, error };
}

export function TenantPicker({
  tenants,
  tenantId,
  onChange,
}: {
  tenants: Tenant[];
  tenantId: string;
  onChange: (id: string) => void;
}) {
  return (
    <select
      aria-label="Workspace"
      value={tenantId}
      onChange={(e) => onChange(e.target.value)}
      className="h-8 cursor-pointer rounded-[var(--rb-r-sm,6px)] border border-neutral-200/70 bg-white px-2 text-[13px] text-neutral-900 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--rb-accent,oklch(20.5%_0_0))] dark:border-neutral-800 dark:bg-neutral-900 dark:text-neutral-100"
    >
      {/* Without this, 2+ tenants display the first one while tenantId is still "" —
          the panel looks selected but loads nothing until the user changes it. */}
      {!tenantId && (
        <option value="" disabled>
          Choose workspace…
        </option>
      )}
      {tenants.map((t) => (
        <option key={t.tenantId} value={t.tenantId}>
          {t.slug} ({t.role})
        </option>
      ))}
    </select>
  );
}
