import type { MetadataRoute } from "next";
import { headers } from "next/headers";

// The landing page is the only public, indexable page; app routes sit behind sign-in.
export default async function sitemap(): Promise<MetadataRoute.Sitemap> {
  const host = (await headers()).get("host") ?? "";
  if (host.startsWith("admin-")) return [];
  return [{ url: `https://${host}/`, changeFrequency: "monthly", priority: 1 }];
}
