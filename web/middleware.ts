import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";
import { requestHost } from "@/lib/request-host";

// admin-* hosts serve the ops surface at the root; the public domain keeps
// the marketing landing. Path-only rewrite — links inside /admin still work.
export function middleware(request: NextRequest) {
  const host = requestHost(request.headers);
  if (host.startsWith("admin-") && request.nextUrl.pathname === "/") {
    const url = request.nextUrl.clone();
    url.pathname = "/admin";
    return NextResponse.rewrite(url);
  }
}

export const config = { matcher: "/" };
