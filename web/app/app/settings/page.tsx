import { Suspense } from "react";
import type { Metadata } from "next";
import { SettingsView } from "@/components/views/settings";

export const metadata: Metadata = { title: "Settings" };

export default function SettingsPage() {
  return (
    <Suspense>
      <SettingsView />
    </Suspense>
  );
}
