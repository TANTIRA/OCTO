import {
  Bell,
  BookOpen,
  Bot,
  Brain,
  Briefcase,
  Building2,
  Database,
  FileText,
  Gauge,
  GitCompareArrows,
  Layers,
  LineChart,
  ListChecks,
  PieChart,
  Scale,
  Settings,
  ShieldCheck,
  Sparkles,
  type LucideIcon,
} from "lucide-react";
import { OPS_COUNTS } from "@/lib/demo";

export type NavItem = {
  id: string;
  label: string;
  href: string;
  icon: LucideIcon;
  /** Actionable count shown as a badge — only for work waiting on someone. */
  badge?: { count: number; tone: "accent" | "danger" };
  /** Not built yet — rendered as a disabled "Planned" row, never a dead link. */
  planned?: boolean;
  /** Letter for the "g then <letter>" shortcut. */
  key?: string;
  /** Shown only to platform admins; the API re-authorizes every privileged call. */
  adminOnly?: boolean;
};

export type NavGroup = { label: string; items: NavItem[] };

/** Information architecture (plan §4 target routes, §38 audit matrix). */
export const NAV: NavGroup[] = [
  {
    label: "Overview",
    items: [
      { id: "control", label: "Control Center", href: "/app", icon: Gauge, key: "h" },
      { id: "portfolio", label: "Portfolio", href: "/app/portfolio", icon: PieChart, key: "p" },
    ],
  },
  {
    label: "Invest",
    items: [
      { id: "funds", label: "Funds", href: "/app/funds", icon: Layers, key: "f" },
      { id: "investments", label: "Investments", href: "/app/investments", icon: Briefcase, key: "i" },
      { id: "companies", label: "Companies", href: "/app/companies", icon: Building2, key: "c" },
      { id: "deals", label: "Deals", href: "/app/deals", icon: BookOpen, key: "d" },
    ],
  },
  {
    label: "Operate",
    items: [
      { id: "workflows", label: "Workflows", href: "/app/workflows", icon: ListChecks, badge: { count: OPS_COUNTS.tasks + OPS_COUNTS.approvals, tone: "accent" }, key: "w" },
      { id: "reconciliation", label: "Reconciliation", href: "/app/reconciliation", icon: GitCompareArrows, key: "r" },
      { id: "compliance", label: "Compliance", href: "/app/compliance", icon: Scale, key: "o" },
      { id: "alerts", label: "Alerts", href: "/app/alerts", icon: Bell, badge: { count: OPS_COUNTS.openAlerts, tone: "danger" }, key: "a" },
      { id: "agents", label: "Agent runs", href: "/app/agents", icon: Bot, key: "u" },
    ],
  },
  {
    label: "Insight",
    items: [
      { id: "analytics", label: "Analytics", href: "/app/analytics", icon: LineChart, key: "n" },
      { id: "reports", label: "Reports", href: "/app/reports", icon: FileText, key: "e" },
      { id: "brain", label: "Company brain", href: "/app/brain", icon: Brain, key: "b" },
      { id: "analysis", label: "AI analysis", href: "/app/analysis", icon: Sparkles, key: "m" },
    ],
  },
  {
    label: "System",
    items: [
      { id: "data", label: "Data & Sources", href: "/app/data", icon: Database, key: "s" },
      { id: "settings", label: "Settings", href: "/app/settings", icon: Settings, key: "," },
      { id: "admin", label: "Administration", href: "/admin", icon: ShieldCheck, adminOnly: true },
    ],
  },
];

export const NAV_ITEMS = NAV.flatMap((g) => g.items);

/** The nav a viewer may see: admin-only rows stay hidden until the API confirms platform admin. */
export function visibleNav(platformAdmin: boolean | null): NavGroup[] {
  return NAV.map((g) => ({ ...g, items: g.items.filter((i) => !i.adminOnly || platformAdmin === true) })).filter((g) => g.items.length > 0);
}

export function isActive(item: NavItem, pathname: string): boolean {
  if (item.href === "/app") return pathname === "/app";
  return pathname === item.href || pathname.startsWith(`${item.href}/`);
}

export type Crumb = { label: string; href?: string };

/** Default breadcrumb from the pathname; object pages extend it with `useBreadcrumb`. */
export function breadcrumb(pathname: string): Crumb[] {
  const item = NAV_ITEMS.find((i) => i.href !== "/app" && isActive(i, pathname));
  if (!item) return [{ label: "Control Center" }];
  const group = NAV.find((g) => g.items.includes(item));
  const deeper = pathname !== item.href;
  return [...(group ? [{ label: group.label }] : []), { label: item.label, href: deeper ? item.href : undefined }];
}
