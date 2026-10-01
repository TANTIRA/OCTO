"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useState,
  type ReactNode,
} from "react";
import { myAccess, messageFor, type Tenant } from "@/lib/api";

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

export type PlatformAdminCheck = {
  // null while the API answers (or after it failed), then the verdict.
  admin: boolean | null;
  error: string | null;
  retry: () => void;
};

/**
 * The platform-admin verdict plus its failure state. Inside TenantProvider it
 * reuses the provider's /me/access read (and its retry); only /admin, which
 * has no provider, reads on its own — through myAccess(), so the same 15 s
 * timeout turns a hung API into a retryable error instead of an endless
 * spinner (#505). A UI gate only — the API re-authorizes every privileged read.
 */
export function usePlatformAdminCheck(): PlatformAdminCheck {
  const shell = useContext(TenantContext);
  const standalone = shell === null;
  const [admin, setAdmin] = useState<boolean | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    if (!standalone) return;
    let cancelled = false;
    setAdmin(null);
    setError(null);
    myAccess()
      .then((access) => {
        if (!cancelled) setAdmin(access.platformAdmin);
      })
      .catch((e) => {
        if (!cancelled) setError(messageFor(e));
      });
    return () => {
      cancelled = true;
    };
  }, [standalone, attempt]);

  const retry = useCallback(() => setAttempt((n) => n + 1), []);

  if (shell) {
    return { admin: shell.platformAdmin, error: shell.error, retry: shell.retry };
  }
  return { admin, error, retry };
}

/**
 * `null` while the API answers, then the platform-admin verdict. Hides the
 * ops entry points in the app shell; a failed read is never `true`.
 */
export function usePlatformAdmin(): boolean | null {
  return usePlatformAdminCheck().admin;
}
