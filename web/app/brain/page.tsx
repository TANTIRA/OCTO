import { redirect } from "next/navigation";
import AuthGate from "@/components/auth-gate";
import BrainPanel from "@/components/brain-panel";
import { TenantProvider } from "@/lib/use-tenants";

// Company brain lives inside the app shell (workspace switcher, nav, ⌘K).
export default function BrainPage() {
  redirect("/app#brain");
  return (
    <AuthGate>
      <TenantProvider>
        <div className="min-h-dvh w-full bg-white dark:bg-neutral-950">
          <BrainPanel />
        </div>
      </TenantProvider>
    </AuthGate>
  );
}
