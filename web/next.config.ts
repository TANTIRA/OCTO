import type { NextConfig } from "next";
import path from "node:path";

const nextConfig: NextConfig = {
  output: "standalone",
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
