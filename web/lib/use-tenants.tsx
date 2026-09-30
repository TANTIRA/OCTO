"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useState,
  type ReactNode,
} from "react";
import { amPlatformAdmin, myAccess, messageFor, type Tenant } from "@/lib/api";

type TenantState = {
  tenants: Tenant[];
  tenantId: string;
  setTenantId: (id: string) => void;
  loading: boolean;
  error: string | null;
  retry: () => void;
  // null until the read lands; false on failure (fail closed).
  platformAdmin: boolean | null;
};

const TenantContext = createContext<TenantState | null>(null);

// Per-browser convenience only — the API re-checks membership on every call.
const STORAGE_KEY = "octo.workspace";

function saved(): string | null {
  try {
    return localStorage.getItem(STORAGE_KEY);
  } catch {
    return null;
  }
}

/**
 * One read of `/api/v1/me/access` for the whole app shell. The workspace
 * switcher writes the selection; every tenant-scoped panel reads it, so
 * there is exactly one answer to "which workspace am I looking at". The API
 * enforces the roles it returns — the UI only narrows choices.
 */
export function TenantProvider({ children }: { children: ReactNode }) {
  const [tenants, setTenants] = useState<Tenant[]>([]);
  const [tenantId, setTenantIdState] = useState("");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);
  const [platformAdmin, setPlatformAdmin] = useState<boolean | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    setPlatformAdmin(null);
    myAccess()
      .then(({ tenants: list, platformAdmin: admin }) => {
        if (cancelled) return;
        const last = saved();
        setTenants(list);
        setPlatformAdmin(admin);
        // Restore the last pick if it is still a membership, else the first —
        // never leave 2+ workspaces with nothing selected (panels would load nothing).
        setTenantIdState(
          list.find((t) => t.tenantId === last)?.tenantId ?? list[0]?.tenantId ?? "",
        );
        setLoading(false);
      })
      .catch((e) => {
        if (cancelled) return;
        setError(messageFor(e));
        setPlatformAdmin(false);
        setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [attempt]);

  const setTenantId = useCallback((id: string) => {
    setTenantIdState(id);
    try {
      localStorage.setItem(STORAGE_KEY, id);
    } catch {
      // Storage blocked (private mode) — the pick still holds for this session.
    }
  }, []);

  const retry = useCallback(() => setAttempt((n) => n + 1), []);

  return (
    <TenantContext.Provider
      value={{ tenants, tenantId, setTenantId, loading, error, retry, platformAdmin }}
    >
      {children}
    </TenantContext.Provider>
  );
}

export function useTenants(): TenantState {
  const state = useContext(TenantContext);
  if (!state) throw new Error("useTenants must be used inside <TenantProvider>");
  return state;
}

/**
 * `null` while the API answers, then the platform-admin verdict. Hides the
 * ops entry points and gates /admin. A UI gate only — every privileged read
 * is re-authorized by the API. Inside TenantProvider it reuses the provider's
 * /me/access read (and follows its retry); only /admin fetches on its own.
 */
export function usePlatformAdmin(): boolean | null {
  const shell = useContext(TenantContext);
  const [admin, setAdmin] = useState<boolean | null>(null);
  const standalone = shell === null;
  useEffect(() => {
    if (!standalone) return;
    let cancelled = false;
    amPlatformAdmin().then((verdict) => {
      if (!cancelled) setAdmin(verdict);
    });
    return () => {
      cancelled = true;
    };
  }, [standalone]);
  return shell ? shell.platformAdmin : admin;
}
