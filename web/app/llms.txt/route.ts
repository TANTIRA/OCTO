import { NextResponse } from "next/server";
import { headers } from "next/headers";
import { requestHost } from "@/lib/request-host";

// What llms.txt may advertise (#557): only pages robots.txt allows on the
// public host. The API root publishes no documentation (#516) and /login is
// disallowed, so neither is listed. The file is not served on admin-* hosts
// at all — robots.txt disallows the whole ops surface there.
const BODY = `# OCTO by Mesta

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

export async function GET(): Promise<Response> {
  const host = requestHost(await headers());
  if (host.startsWith("admin-")) return new NextResponse(null, { status: 404 });
  return new NextResponse(BODY, {
    headers: { "content-type": "text/plain; charset=utf-8" },
  });
}
