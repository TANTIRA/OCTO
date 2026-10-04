import { headers } from "next/headers";
import { requestHost } from "@/lib/request-host";
import { llmsTxtForHost } from "@/lib/llms-txt";

// Host-aware, same split as robots.ts. A static public/llms.txt would be
// served on the admin-* ops host, which is Disallow: /.
export const dynamic = "force-dynamic";

export async function GET(): Promise<Response> {
  const decision = llmsTxtForHost(requestHost(await headers()));
  return new Response(decision.body, {
    status: decision.status,
    headers: {
      "Content-Type": "text/plain; charset=utf-8",
      "Cache-Control": "private, no-store",
      Vary: "x-forwarded-host, host",
    },
  });
}
