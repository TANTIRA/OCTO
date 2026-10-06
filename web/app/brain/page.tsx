import { redirect } from "next/navigation";

// Company brain lives inside the app shell (workspace switcher, nav, ⌘K).
export default function BrainPage() {
  redirect("/app/brain");
}
