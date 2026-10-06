/**
 * Demo ontology objects: funds → investments → companies, plus the deal
 * pipeline. Illustrative only — every surface that reads this shows a
 * "Demo data" badge. Numbers reconcile: investment fair values sum to fund
 * NAV less fund cash, and fund NAVs sum to portfolio NAV ($812.4M).
 */

export const DEMO_NOW = "2026-09-30T13:42:00Z";
export const AS_OF = "2026-09-30T00:00:00Z";

export const at = (hoursAgo: number) => new Date(new Date(DEMO_NOW).getTime() - hoursAgo * 3600_000).toISOString();
export const daysAgo = (d: number) => at(d * 24);

export type Severity = "critical" | "high" | "medium" | "low";
export const SEVERITY_ORDER: Record<Severity, number> = { critical: 0, high: 1, medium: 2, low: 3 };

/* ---------- Funds ---------- */

export type FundStatus = "Investing" | "Harvesting" | "Watch" | "Exiting" | "Fundraising";

export type Fund = {
  id: string;
  slug: string;
  name: string;
  short: string;
  vintage: number;
  strategy: string;
  status: FundStatus;
  geography: string;
  committed: number;
  called: number;
  nav: number;
  cash: number;
  distributions: number;
  netIrr: number;
  grossIrr: number;
  manager: string;
};

const FUND_ROWS: Omit<Fund, "slug">[] = [
  { id: "FND-002", name: "OCTO Flagship Fund II", short: "Flagship II", vintage: 2023, strategy: "Buyout", status: "Investing", geography: "Southeast Asia", committed: 600e6, called: 342.0e6, nav: 486.2e6, cash: 18.4e6, distributions: 101.5e6, netIrr: 19.4, grossIrr: 24.8, manager: "A. Wijaya" },
  { id: "FND-001", name: "OCTO Flagship Fund I", short: "Flagship I", vintage: 2019, strategy: "Buyout", status: "Harvesting", geography: "Southeast Asia", committed: 250e6, called: 214.0e6, nav: 184.7e6, cash: 6.2e6, distributions: 138.7e6, netIrr: 17.1, grossIrr: 22.3, manager: "R. Tan" },
  { id: "FND-003", name: "OCTO Opportunities I", short: "Opportunities I", vintage: 2024, strategy: "Growth / credit", status: "Investing", geography: "Asia Pacific", committed: 200e6, called: 58.3e6, nav: 96.4e6, cash: 9.1e6, distributions: 4.8e6, netIrr: 14.8, grossIrr: 19.2, manager: "M. Sari" },
  { id: "FND-004", name: "Antero Co-Invest SPV", short: "Antero SPV", vintage: 2025, strategy: "Single deal", status: "Watch", geography: "Indonesia", committed: 30e6, called: 28.1e6, nav: 31.5e6, cash: 0.6e6, distributions: 0, netIrr: 9.6, grossIrr: 11.4, manager: "A. Wijaya" },
  { id: "FND-005", name: "OCTO Venture FoF", short: "Venture FoF", vintage: 2022, strategy: "Fund of funds", status: "Exiting", geography: "Global", committed: 20e6, called: 2.4e6, nav: 13.6e6, cash: 1.3e6, distributions: 0, netIrr: -2.1, grossIrr: -0.4, manager: "D. Lim" },
];

export const FUNDS: Fund[] = FUND_ROWS.map((f) => ({ ...f, slug: f.id.toLowerCase() }));

export const fundTvpi = (f: Fund) => (f.distributions + f.nav) / f.called;
export const fundDpi = (f: Fund) => f.distributions / f.called;
export const fundDryPowder = (f: Fund) => f.committed - f.called;

/* ---------- Companies ---------- */

export type CompanyStatus = "Performing" | "Watch" | "Covenant breach" | "Exited";

export type Company = {
  id: string;
  name: string;
  sector: string;
  subsector: string;
  geography: string;
  hq: string;
  founded: number;
  stage: "Growth" | "Mature" | "Early";
  status: CompanyStatus;
  owner: string;
  employees: number;
  revenue: number;
  revenueGrowth: number;
  ebitda: number;
  netDebt: number;
  description: string;
  risks: string[];
  latestEvent: { title: string; at: string };
};

type CompanySeed = [id: string, name: string, sector: string, subsector: string, geography: string, hq: string, founded: number, stage: Company["stage"], status: CompanyStatus, owner: string, employees: number, revenueM: number, growth: number, ebitdaM: number, netDebtM: number, description: string, risks: string[], event: string, eventDaysAgo: number];

const COMPANY_SEEDS: CompanySeed[] = [
  ["CMP-0211", "Helios Data Centers", "Digital infrastructure", "Colocation", "Indonesia", "Jakarta", 2014, "Growth", "Covenant breach", "R. Tan", 420, 96.4, 18.2, 38.1, 212.0, "Tier III colocation operator with 42 MW across three Jakarta campuses.", ["DSCR below covenant", "Refinancing"], "Q3 compliance certificate shows DSCR 1.14×", 0.1],
  ["CMP-0187", "Meridian Health", "Healthcare", "Hospitals", "Vietnam", "Ho Chi Minh City", 2009, "Mature", "Watch", "M. Sari", 3100, 182.0, 3.8, 14.2, 64.0, "Network of 11 private hospitals and 40 clinics in southern Vietnam.", ["Budget variance"], "Selected as preferred bidder in Ministry tender", 0.8],
  ["CMP-0192", "Kirana Consumer", "Consumer", "Modern retail", "Indonesia", "Surabaya", 2011, "Growth", "Watch", "A. Wijaya", 5400, 241.0, 11.5, 28.9, 167.0, "Convenience-store chain with 1,180 outlets across East Java.", ["Leverage above ceiling"], "Add-on acquisition sent to IC", 1],
  ["CMP-0203", "Serayu Renewables", "Renewables", "Hydro & geothermal", "Indonesia", "Bandung", 2016, "Growth", "Performing", "R. Tan", 260, 74.5, 22.4, 41.0, 188.0, "Run-of-river hydro and geothermal IPP with 310 MW operating.", [], "Stock dividend declared", 2],
  ["CMP-0198", "Aruna Payments", "Fintech", "Payments", "Philippines", "Manila", 2018, "Growth", "Watch", "D. Lim", 610, 38.2, 46.0, 4.1, -12.0, "Merchant acquiring and QR payments for 210k SMEs.", ["Key person"], "CFO resignation reported", 6],
  ["CMP-0215", "Solus Energy Partners", "Renewables", "Solar C&I", "Thailand", "Bangkok", 2017, "Growth", "Watch", "M. Sari", 180, 52.8, 19.1, 21.6, 96.0, "Commercial and industrial rooftop solar under 15-year PPAs.", ["Stale valuation"], "Last approved mark 16 Aug 2026", 0.3],
  ["CMP-0176", "Nusantara Logistics", "Industrials", "Cold chain", "Indonesia", "Jakarta", 2008, "Mature", "Performing", "A. Wijaya", 2200, 134.0, 9.2, 19.8, 71.0, "Temperature-controlled warehousing and last-mile for FMCG.", [], "New Cikarang hub commissioned", 9],
  ["CMP-0181", "Lumen Education", "Education", "K-12", "Malaysia", "Kuala Lumpur", 2012, "Mature", "Performing", "D. Lim", 1400, 61.0, 7.4, 16.3, 22.0, "Premium bilingual schools, 14 campuses and 19k students.", [], "Enrollment up 6% for new academic year", 12],
  ["CMP-0189", "Tidewater Aquaculture", "Consumer", "Seafood", "Vietnam", "Can Tho", 2010, "Mature", "Performing", "M. Sari", 900, 88.0, 5.1, 11.9, 34.0, "Vertically integrated shrimp farming and processing for export.", ["FX exposure"], "EU export licence renewed", 20],
  ["CMP-0206", "Cendana Pharma", "Healthcare", "Generics", "Indonesia", "Semarang", 2005, "Mature", "Performing", "R. Tan", 1700, 118.0, 8.8, 22.4, 45.0, "Generic pharmaceuticals with 280 registered SKUs.", [], "BPOM approval for 12 new SKUs", 15],
  ["CMP-0210", "Arcadia Software", "Technology", "Vertical SaaS", "Singapore", "Singapore", 2015, "Growth", "Performing", "D. Lim", 340, 29.5, 38.0, 6.2, -18.0, "Property-management SaaS used by 2,400 buildings in ASEAN.", [], "ARR crossed $30M", 4],
  ["CMP-0199", "Pelita Water", "Infrastructure", "Water utilities", "Philippines", "Cebu", 2013, "Mature", "Performing", "A. Wijaya", 520, 44.0, 6.1, 20.8, 88.0, "Bulk water concession serving 1.3M people in Cebu.", [], "Tariff adjustment approved", 25],
  ["CMP-0214", "Kopi Nusa", "Consumer", "F&B chains", "Indonesia", "Jakarta", 2017, "Growth", "Performing", "M. Sari", 3800, 67.0, 24.0, 8.4, 12.0, "Grab-and-go coffee chain with 640 outlets.", [], "Opened 60 outlets in Q3", 7],
  ["CMP-0183", "Mekong Cold Storage", "Industrials", "Cold chain", "Vietnam", "Hai Phong", 2012, "Mature", "Performing", "R. Tan", 480, 31.0, 7.0, 9.6, 27.0, "Port-side cold storage with 90k pallet positions.", [], "Utilisation 91% in September", 30],
  ["CMP-0220", "Garuda Fibre", "Digital infrastructure", "Fibre networks", "Indonesia", "Medan", 2019, "Early", "Performing", "R. Tan", 390, 22.0, 61.0, 7.8, 54.0, "FTTH network with 410k homes passed in North Sumatra.", [], "Passed 400k homes", 11],
  ["CMP-0222", "Anchor Re Holdings", "Financial services", "Reinsurance", "Singapore", "Singapore", 2010, "Mature", "Performing", "D. Lim", 150, 210.0, 4.2, 26.0, 0, "Specialty property and marine reinsurance.", ["Cat exposure"], "Renewal season priced +7%", 18],
  ["CMP-0225", "Sinar Agritech", "Agriculture", "Precision farming", "Indonesia", "Lampung", 2020, "Early", "Watch", "M. Sari", 210, 9.8, 33.0, -1.2, 6.0, "Drone and sensor services for 180k hectares of plantations.", ["Cash runway"], "Bridge round in discussion", 3],
  ["CMP-0228", "Harbor Logistics", "Industrials", "Freight forwarding", "Thailand", "Laem Chabang", 2006, "Mature", "Exited", "A. Wijaya", 0, 0, 0, 0, 0, "Freight forwarder — fully realised in 2025 at 2.9× gross.", [], "Final escrow released", 60],
];

export const COMPANIES: Company[] = COMPANY_SEEDS.map(([id, name, sector, subsector, geography, hq, founded, stage, status, owner, employees, rev, growth, ebitda, nd, description, risks, event, eventDays]) => ({
  id,
  name,
  sector,
  subsector,
  geography,
  hq,
  founded,
  stage,
  status,
  owner,
  employees,
  revenue: rev * 1e6,
  revenueGrowth: growth,
  ebitda: ebitda * 1e6,
  netDebt: nd * 1e6,
  description,
  risks,
  latestEvent: { title: event, at: daysAgo(eventDays) },
}));

/* ---------- Investments (positions) ---------- */

export type Instrument = "Common equity" | "Preferred equity" | "Convertible" | "Senior loan";
export type Realization = "Unrealized" | "Partially realized" | "Realized";

export type Investment = {
  id: string;
  companyId: string;
  fundId: string;
  instrument: Instrument;
  entryDate: string;
  cost: number;
  fairValue: number;
  realized: number;
  ownership: number;
  irr: number;
  qtdChange: number;
  valuationDate: string;
  valuationAgeDays: number;
  realization: Realization;
  riskStatus: "On track" | "Watch" | "At risk";
  trend: number[];
};

/* [companyId, fundId, instrument, entry year, weight in fund, ownership %, gross IRR, QTD %, valuation age days, cost multiple (cost = fv / m)] */
type InvSeed = [string, string, Instrument, number, number, number, number, number, number, number];
const INV_SEEDS: InvSeed[] = [
  ["CMP-0211", "FND-002", "Common equity", 2023, 16, 62, 21.4, -4.1, 12, 1.35],
  ["CMP-0192", "FND-002", "Common equity", 2023, 18, 55, 24.9, 3.2, 12, 1.52],
  ["CMP-0203", "FND-002", "Common equity", 2024, 14, 48, 31.2, 6.8, 12, 1.61],
  ["CMP-0176", "FND-002", "Common equity", 2023, 12, 70, 18.3, 2.1, 12, 1.33],
  ["CMP-0214", "FND-002", "Preferred equity", 2024, 9, 34, 27.5, 5.4, 12, 1.44],
  ["CMP-0220", "FND-002", "Common equity", 2025, 8, 51, 22.0, 9.1, 12, 1.18],
  ["CMP-0206", "FND-002", "Common equity", 2024, 11, 45, 17.4, 1.2, 12, 1.21],
  ["CMP-0210", "FND-002", "Preferred equity", 2025, 8, 22, 29.8, 7.7, 12, 1.24],
  ["CMP-0187", "FND-001", "Common equity", 2019, 30, 58, 14.2, -2.6, 12, 1.49],
  ["CMP-0181", "FND-001", "Common equity", 2020, 22, 64, 19.8, 1.9, 12, 1.87],
  ["CMP-0189", "FND-001", "Common equity", 2019, 18, 71, 12.1, 0.8, 12, 1.42],
  ["CMP-0199", "FND-001", "Senior loan", 2021, 14, 0, 9.6, 0.4, 12, 1.08],
  ["CMP-0183", "FND-001", "Common equity", 2020, 16, 66, 16.0, 1.1, 12, 1.63],
  ["CMP-0215", "FND-003", "Convertible", 2024, 34, 28, 13.4, 0, 45, 1.19],
  ["CMP-0198", "FND-003", "Preferred equity", 2024, 30, 19, 16.9, -3.4, 12, 1.27],
  ["CMP-0222", "FND-003", "Preferred equity", 2025, 26, 12, 11.2, 1.6, 12, 1.09],
  ["CMP-0225", "FND-003", "Convertible", 2025, 10, 15, -8.0, -6.2, 33, 0.82],
  ["CMP-0192", "FND-004", "Common equity", 2025, 100, 9, 9.6, 2.3, 12, 1.12],
  ["CMP-0210", "FND-005", "Common equity", 2022, 55, 3, -1.8, 4.2, 40, 0.97],
  ["CMP-0198", "FND-005", "Common equity", 2022, 45, 2, -3.1, -2.2, 40, 0.88],
];

/**
 * Eight quarter-end values ending at `end`: compounding at the annual `drift`
 * through Q-1, then the last step is the position's own QTD move — so a
 * position that fell this quarter never draws a rising line.
 */
function wave(seed: number, end: number, drift: number, qtd: number): number[] {
  const prior = end / (1 + qtd / 100);
  const out: number[] = [];
  let v = prior / Math.pow(1 + drift / 100, 6 / 4);
  for (let i = 0; i < 8; i++) {
    const noise = Math.sin(seed * 12.9898 + i * 78.233) * 0.02;
    out.push(i === 7 ? end : i === 6 ? prior : v * (1 + noise));
    v *= Math.pow(1 + drift / 100, 1 / 4);
  }
  return out.map((x) => Math.round(x / 1e4) / 1e2);
}

export const INVESTMENTS: Investment[] = (() => {
  const out: Investment[] = [];
  for (const f of FUNDS) {
    const rows = INV_SEEDS.filter((s) => s[1] === f.id);
    const total = rows.reduce((n, r) => n + r[4], 0);
    rows.forEach((r, i) => {
      const [companyId, fundId, instrument, year, weight, ownership, irr, qtd, age, mult] = r;
      const fv = Math.round(((f.nav - f.cash) * weight) / total / 1e4) * 1e4;
      const cost = Math.round(fv / mult / 1e4) * 1e4;
      out.push({
        id: `INV-${fundId.slice(-1)}${String(i + 1).padStart(2, "0")}`,
        companyId,
        fundId,
        instrument,
        entryDate: `${year}-0${(i % 9) + 1}-15T00:00:00Z`,
        cost,
        fairValue: fv,
        realized: fundId === "FND-001" ? Math.round(cost * 0.35 / 1e4) * 1e4 : 0,
        ownership,
        irr,
        qtdChange: qtd,
        valuationDate: daysAgo(age),
        valuationAgeDays: age,
        realization: fundId === "FND-001" ? "Partially realized" : "Unrealized",
        riskStatus: irr < 0 || qtd < -4 ? "At risk" : qtd < 0 || age > 30 ? "Watch" : "On track",
        trend: wave(i + fundId.charCodeAt(6), fv, irr, qtd),
      });
    });
  }
  // The residual from rounding is absorbed by the largest position so NAV reconciles exactly.
  for (const f of FUNDS) {
    const rows = out.filter((x) => x.fundId === f.id);
    const diff = f.nav - f.cash - rows.reduce((n, x) => n + x.fairValue, 0);
    const big = rows.reduce((a, b) => (b.fairValue > a.fairValue ? b : a));
    big.fairValue += diff;
  }
  return out;
})();

export const investmentMoic = (i: Investment) => (i.fairValue + i.realized) / i.cost;

/* ---------- Lookups ---------- */

export const fundById = (id: string) => FUNDS.find((f) => f.id === id || f.slug === id);
export const companyById = (id: string) => COMPANIES.find((c) => c.id === id || c.id.toLowerCase() === id);
export const investmentsForFund = (fundId: string) => INVESTMENTS.filter((i) => i.fundId === fundId);
export const investmentsForCompany = (companyId: string) => INVESTMENTS.filter((i) => i.companyId === companyId);

export const PORTFOLIO = (() => {
  const nav = FUNDS.reduce((n, f) => n + f.nav, 0);
  const called = FUNDS.reduce((n, f) => n + f.called, 0);
  const dist = FUNDS.reduce((n, f) => n + f.distributions, 0);
  const committed = FUNDS.reduce((n, f) => n + f.committed, 0);
  const cost = INVESTMENTS.reduce((n, i) => n + i.cost, 0);
  const cash = FUNDS.reduce((n, f) => n + f.cash, 0);
  return { nav, called, distributions: dist, committed, dryPowder: committed - called, invested: cost, cash, tvpi: (dist + nav) / called, dpi: dist / called, netIrr: 18.2, grossIrr: 23.1 };
})();
