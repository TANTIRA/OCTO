import type { NextConfig } from "next";
import path from "node:path";

// The only cross-origin destination the browser is allowed to reach: the
// Supabase auth/API gateway. Baked at build time because NEXT_PUBLIC_* is.
const supabaseOrigin = (() => {
  const url = process.env.NEXT_PUBLIC_SUPABASE_URL;
  if (!url) return null;
  try {
    return new URL(url).origin;
  } catch {
    return null;
  }
})();

const securityHeaders = [
  { key: "X-Content-Type-Options", value: "nosniff" },
  { key: "X-Frame-Options", value: "DENY" },
  { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
  { key: "Cross-Origin-Opener-Policy", value: "same-origin" },
  {
    key: "Strict-Transport-Security",
    value: "max-age=31536000; includeSubDomains",
  },
  // No camera/mic/geo surface anywhere in the app — deny them all.
  {
    key: "Permissions-Policy",
    value: "camera=(), microphone=(), geolocation=(), payment=(), usb=()",
  },
  {
    key: "Content-Security-Policy",
    value: [
      "default-src 'self'",
      // Next hydrates from inline RSC payloads; 'unsafe-inline' is the cost of
      // not running per-request nonces.
      "script-src 'self' 'unsafe-inline'",
      "style-src 'self' 'unsafe-inline'",
      "img-src 'self' data: blob:",
      "font-src 'self'",
      `connect-src 'self'${supabaseOrigin ? ` ${supabaseOrigin}` : ""}`,
      "frame-ancestors 'none'",
      "base-uri 'self'",
    ].join("; "),
  },
];

const nextConfig: NextConfig = {
  output: "standalone",
  async headers() {
    return [{ source: "/:path*", headers: securityHeaders }];
  },
  // The repo is not a workspace — but a stray lockfile in a parent directory makes
  // Next infer a monorepo root and nest the standalone output. Pin tracing to web/
  // so .next/standalone/server.js lands at the root the Dockerfile CMD expects.
  outputFileTracingRoot: path.join(__dirname),
  // Browser → same-origin /api/* → internal API service. Keeps the API off the
  // public web origin entirely (no CORS surface); Authorization headers pass
  // through to the resource server. API_INTERNAL_URL is a runtime env — compose
  // sets http://api:8080; local dev falls back to bootRun on localhost.
  async rewrites() {
    const api = process.env.API_INTERNAL_URL ?? "http://localhost:8080";
    return [{ source: "/api/:path*", destination: `${api}/api/:path*` }];
  },
};

export default nextConfig;
