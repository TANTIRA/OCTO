"use client";

import AuthGate from "@/components/auth-gate";
import { cn } from "@/lib/utils";
import { PreferencesProvider, usePreferences } from "@/lib/preferences";
import { TenantProvider } from "@/lib/use-tenants";
import { WorkspaceProvider } from "@/lib/workspace";
import { QueryProvider } from "@/lib/data/queries";
import { ToastProvider } from "@/components/feedback";
import { AppShell } from "./app-shell";
import { ShellProvider } from "./shell-context";

/**
 * Root of every /app route: preferences → theme scope → query client → toasts
 * → shell context → session gate → tenants (one /me/access read) → workspace
 * view → shell. Tenants load only behind the session gate, so an anonymous
 * visitor never triggers an API call. The `dark` class only ever sits on
 * `.octo-app`, so the landing page is never affected.
 */
export function AppFrame({ className, children }: { className?: string; children: React.ReactNode }) {
  return (
    <PreferencesProvider>
      <Themed className={className}>
        <QueryProvider>
          <ToastProvider>
            <ShellProvider>
              <AuthGate>
                <TenantProvider>
                  <WorkspaceProvider>
                    <AppShell>{children}</AppShell>
                  </WorkspaceProvider>
                </TenantProvider>
              </AuthGate>
            </ShellProvider>
          </ToastProvider>
        </QueryProvider>
      </Themed>
    </PreferencesProvider>
  );
}

function Themed({ className, children }: { className?: string; children: React.ReactNode }) {
  const { locale, resolvedTheme } = usePreferences();
  // V3 THEME-003: Light by default; Dark/System resolve to the full dark token mapping.
  return (
    <div lang={locale === "id" ? "id" : "en"} data-theme={resolvedTheme} className={cn("octo-app min-h-dvh bg-app font-landing text-ink antialiased [font-feature-settings:'cv11','ss01']", resolvedTheme === "dark" && "dark", className)} style={{ colorScheme: resolvedTheme }}>
      {children}
    </div>
  );
}
