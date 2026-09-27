import AppShell2 from "@/components/blocks/app-shell-2";
import CommandMenu1 from "@/components/blocks/command-menu-1";
import AuthGate from "@/components/auth-gate";

export default function AppPage() {
  return (
    <AuthGate>
      <div className="h-dvh w-full">
        <AppShell2 />
        <CommandMenu1 />
      </div>
    </AuthGate>

export default function AppPage() {
  return (
    <div className="h-dvh w-full">
      <AppShell2 />
      <CommandMenu1 />
    </div>
  );
}
