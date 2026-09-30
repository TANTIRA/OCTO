import AuthGate from "@/components/auth-gate";
import BrainPanel from "@/components/brain-panel";
import { TenantProvider } from "@/lib/use-tenants";

export default function BrainPage() {
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
