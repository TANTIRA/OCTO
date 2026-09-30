import type { NextConfig } from "next";
import path from "node:path";

// The only cross-origin destinations the browser is allowed to reach: the
// Supabase auth/API gateway and the Solana RPC endpoint. Baked at build time
// because NEXT_PUBLIC_* is.
const originOf = (url: string | undefined) => {
  if (!url) return null;
  try {
    return new URL(url).origin;
  } catch {
    return null;
  }
};
const supabaseOrigin = originOf(process.env.NEXT_PUBLIC_SUPABASE_URL);
// wallet-sign-in falls back to clusterApiUrl("devnet") when the env is unset —
// mirror that so connect-src covers whatever endpoint the bundle bakes in.
const rpcOrigin = originOf(
  process.env.NEXT_PUBLIC_SOLANA_RPC_URL ?? "https://api.devnet.solana.com",
);
const connectOrigins = [...new Set([supabaseOrigin, rpcOrigin].filter(Boolean))].join(" ");

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
      // not running per-request nonces. react-refresh eval()s modules in dev —
      // unsafe-eval is dev-only, never in the production policy.
      `script-src 'self' 'unsafe-inline'${process.env.NODE_ENV === "development" ? " 'unsafe-eval'" : ""}`,
      "style-src 'self' 'unsafe-inline'",
      // cta-2's pointer trail loads Unsplash stills — allow that one origin.
      "img-src 'self' data: blob: https://images.unsplash.com",
      "font-src 'self'",
      `connect-src 'self'${connectOrigins ? ` ${connectOrigins}` : ""}`,
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
    return [
      { source: "/api/:path*", destination: `${api}/api/:path*` },
      // /admin probes actuator same-origin so the internal API base URL
      // stays server-side. Actuator health is permitAll in SecurityConfig —
      // this exposes nothing the API doesn't already serve publicly.
      { source: "/ops-api/actuator/:path*", destination: `${api}/actuator/:path*` },
    ];
  },
};

export default nextConfig;
