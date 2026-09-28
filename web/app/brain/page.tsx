import AuthGate from "@/components/auth-gate";
import BrainPanel from "@/components/brain-panel";

export default function BrainPage() {
  return (
    <AuthGate>
      <div className="min-h-dvh w-full bg-white dark:bg-neutral-950">
        <BrainPanel />
      </div>
    </AuthGate>
  );
}
