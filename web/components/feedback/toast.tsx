"use client";

import { createContext, useCallback, useContext, useState } from "react";
import { AlertTriangle, CheckCircle2, Info, X } from "lucide-react";
import { IconButton } from "@/components/ui/button";

type ToastItem = { id: number; title: string; body?: string; tone: "ok" | "info" | "danger" };
const ToastCtx = createContext<(t: Omit<ToastItem, "id">) => void>(() => undefined);

export function ToastProvider({ children }: { children: React.ReactNode }) {
  const [items, setItems] = useState<ToastItem[]>([]);
  const push = useCallback((t: Omit<ToastItem, "id">) => {
    const id = Date.now() + Math.random();
    setItems((xs) => [...xs, { ...t, id }]);
    window.setTimeout(() => setItems((xs) => xs.filter((x) => x.id !== id)), 5000);
  }, []);
  return (
    <ToastCtx.Provider value={push}>
      {children}
      <div aria-live="polite" className="pointer-events-none fixed bottom-4 right-4 z-[90] flex w-[min(20rem,calc(100vw-2rem))] flex-col gap-2">
        {items.map((t) => (
          <div key={t.id} role="status" className="pointer-events-auto flex items-start gap-3 rounded-lg border border-line bg-raised p-3 text-ink shadow-popover motion-safe:animate-[pop-in_160ms_var(--ease-out-soft)]">
            {t.tone === "ok" && <CheckCircle2 aria-hidden className="mt-0.5 size-4 shrink-0 text-ok" />}
            {t.tone === "info" && <Info aria-hidden className="mt-0.5 size-4 shrink-0 text-info" />}
            {t.tone === "danger" && <AlertTriangle aria-hidden className="mt-0.5 size-4 shrink-0 text-danger" />}
            <div className="min-w-0 flex-1">
              <p className="text-[13px] font-medium">{t.title}</p>
              {t.body && <p className="mt-0.5 text-[12px] text-ink-3">{t.body}</p>}
            </div>
            <IconButton size="sm" label="Dismiss" icon={<X />} onClick={() => setItems((xs) => xs.filter((x) => x.id !== t.id))} />
          </div>
        ))}
      </div>
    </ToastCtx.Provider>
  );
}

export const useToast = () => useContext(ToastCtx);
