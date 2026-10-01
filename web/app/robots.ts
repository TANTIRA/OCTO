import type { MetadataRoute } from "next";
import { headers } from "next/headers";

// One image serves the public site and the admin-* ops host (see middleware.ts):
// the public host exposes only the landing page; the admin host is never indexed.
export default async function robots(): Promise<MetadataRoute.Robots> {
  const host = (await headers()).get("host") ?? "";
  if (host.startsWith("admin-")) {
    return { rules: { userAgent: "*", disallow: "/" } };
  }
  return {
    rules: { userAgent: "*", allow: "/", disallow: ["/app", "/admin", "/login", "/api/"] },
    sitemap: `https://${host}/sitemap.xml`,
  };
}
