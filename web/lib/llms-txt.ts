/**
 * Agent-facing summary of the public marketing site.
 *
 * robots.ts allows `/` and disallows `/app`, `/admin`, `/login`, and `/api/`.
 * The admin-* host disallows every path, so this document is not served there.
 * Sign-in and the API root are omitted: `/login` is disallowed, and the API
 * root publishes no documentation. Only the landing page and its public
 * contact anchor are listed.
 *
 * No path aliases so node:test can import this directly.
 */

export const LLMS_TXT = `# OCTO by Mesta

> One book of record for private markets. OCTO puts funds, deals, portfolio companies, and LP reporting on one shared, self-hosted record for private-equity firms and allocators. It is in private beta.

- Positions and cash are calculated from the transaction history, never typed in, so any figure can be traced back to the event that produced it.
- Nothing is overwritten: a mistake is fixed by adding a correction that points to the original entry and states a reason. Duplicate imports are rejected.
- One ontology links the objects: a fund manager manages a fund, a limited partner commits to it, and the fund invests via a deal whose subject is an operating company. Screening, diligence, analytics, IC memos, and LP reports all read from it.
- Sources: CRM, fund administrators, custodians, market data, documents, on-chain activity, cap tables, and spreadsheets.
- Self-hosted: the ledger and ontology run on the customer's own infrastructure. Each firm's data is isolated inside the database, and every request is checked before anything is returned.
- AI sorts documents, checks whether claims are backed by their sources, and suggests screening outcomes. Every result records the model behind it, confidential data only goes to zero-data-retention models, and important actions need a person's approval.

## Pages

- [Landing page](https://octo.mesta.click/): product overview, platform, ontology, security, and FAQ
- [Request access](https://octo.mesta.click/#contact): form to book a working session; a specialist follows up by email
`;

/** Path prefixes robots.ts disallows on the public host, without a trailing slash. */
const DISALLOWED_PREFIXES = ["/app", "/admin", "/login", "/api"] as const;

const PUBLIC_HOST = "octo.mesta.click";

/**
 * The admin-* ops host is `Disallow: /` in robots.ts. An empty host is treated
 * as the public site, matching robots.ts.
 */
export function servesLlmsTxt(host: string): boolean {
  return !host.trim().startsWith("admin-");
}

/** Markdown destinations in the document. */
export function llmsTxtLinks(body: string): string[] {
  return [...body.matchAll(/\]\(([^)\s]+)\)/g)].map((match) => match[1]);
}

/**
 * A link agents may follow. It has to be the public marketing origin, on `/`,
 * with only the in-page contact anchor — never a path robots.ts disallows
 * and never the admin or API host.
 */
export function isPublicLlmsLink(raw: string): boolean {
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    return false;
  }
  if (url.protocol !== "https:" || url.username !== "" || url.password !== "") return false;
  if (url.hostname !== PUBLIC_HOST || url.port !== "") return false;
  if (url.search !== "") return false;
  if (url.hash !== "" && url.hash !== "#contact") return false;
  const path = url.pathname;
  const disallowed = DISALLOWED_PREFIXES.some(
    (prefix) => path === prefix || path.startsWith(`${prefix}/`),
  );
  return path === "/" && !disallowed;
}

export function llmsTxtForHost(host: string): { status: 200 | 404; body: string } {
  const links = llmsTxtLinks(LLMS_TXT);
  const publishable =
    servesLlmsTxt(host) &&
    links.length > 0 &&
    links.every(isPublicLlmsLink) &&
    !LLMS_TXT.includes("/login") &&
    !LLMS_TXT.includes("api-octo") &&
    !LLMS_TXT.includes("admin-");
  if (!publishable) return { status: 404, body: "Not Found\n" };
  return { status: 200, body: LLMS_TXT };
}
