import { NAV_ITEMS } from "./nav-config";

/**
 * Keyboard shortcut registry (plan FE-SHELL-001). The help menu and the
 * global handler both read from here, so documentation cannot drift from
 * behaviour.
 */
export type Shortcut = { keys: string[]; label: string; group: "Global" | "Navigation" | "Tables" };

export const SHORTCUTS: Shortcut[] = [
  { keys: ["Ctrl", "K"], label: "Search and commands", group: "Global" },
  { keys: ["/"], label: "Search", group: "Global" },
  { keys: ["["], label: "Collapse or expand sidebar", group: "Global" },
  { keys: ["?"], label: "Show keyboard shortcuts", group: "Global" },
  { keys: ["Esc"], label: "Close sheet, menu or dialog", group: "Global" },
  ...NAV_ITEMS.filter((n) => n.key).map((n) => ({ keys: ["G", n.key === "," ? "," : n.key!.toUpperCase()], label: `Go to ${n.label}`, group: "Navigation" as const })),
  { keys: ["↑", "↓"], label: "Move between rows", group: "Tables" },
  { keys: ["Enter"], label: "Open row", group: "Tables" },
  { keys: ["Space"], label: "Select row", group: "Tables" },
  { keys: ["→", "←"], label: "Expand or collapse row", group: "Tables" },
  { keys: ["Shift", "Click"], label: "Add a secondary sort", group: "Tables" },
];

export const GO_TO: Record<string, string> = Object.fromEntries(NAV_ITEMS.filter((n) => n.key).map((n) => [n.key!, n.href]));
