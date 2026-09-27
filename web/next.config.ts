import type { NextConfig } from "next";
import path from "node:path";

const nextConfig: NextConfig = {
  output: "standalone",
  // The repo is not a workspace — but a stray lockfile in a parent directory makes
  // Next infer a monorepo root and nest the standalone output. Pin tracing to web/
  // so .next/standalone/server.js lands at the root the Dockerfile CMD expects.
  outputFileTracingRoot: path.join(__dirname),
};

export default nextConfig;
