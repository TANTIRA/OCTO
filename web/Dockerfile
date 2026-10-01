# OCTO web image: infra/docker-compose.yml pulls ${REGISTRY_URL}/octo-web:${WEB_IMAGE_TAG};
# deploy/dokploy.compose.yml builds it on the Dokploy host. Build context is web/.
# Build from the repo root:  docker build -t "$REGISTRY_URL/octo-web:$WEB_IMAGE_TAG" -f web/Dockerfile web
#
# next.config.ts sets output: "standalone" — `next build` emits .next/standalone,
# a pruned server bundle; the runtime stage carries only that, static assets, and
# public files. No dev dependencies, no sources.
#
# Base images are pinned by version + digest (infra/README.md guardrails). Bump both together:
#   docker buildx imagetools inspect node:22.23.3-alpine --format '{{.Manifest.Digest}}'

# Not /app: with the project root at /app, next build maps the root route onto
# app/app/page.tsx (the dashboard), so / served the auth-gated shell instead of
# the landing page. Any other directory builds the routes correctly.
FROM node:22.23.3-alpine@sha256:0a7108bf6c7bf5de370ffb1a3ed6be93d405b43ff159f681a8d18c0e2bc2e402 AS deps
WORKDIR /srv/web
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

FROM node:22.23.3-alpine@sha256:0a7108bf6c7bf5de370ffb1a3ed6be93d405b43ff159f681a8d18c0e2bc2e402 AS build
WORKDIR /srv/web
COPY --from=deps /srv/web/node_modules ./node_modules
COPY . .
# NEXT_PUBLIC_* is inlined into the client bundle at build time — the anon key
# is publishable by design (security lives server-side), so baking it in is safe.
ARG NEXT_PUBLIC_SUPABASE_URL
ARG NEXT_PUBLIC_SUPABASE_ANON_KEY
# Optional wallet-adapter RPC endpoint — SIWS signs locally and never calls
# it, so leaving it unset is fine (wallet-sign-in falls back to devnet).
ARG NEXT_PUBLIC_SOLANA_RPC_URL=
ENV NEXT_PUBLIC_SUPABASE_URL=$NEXT_PUBLIC_SUPABASE_URL \
    NEXT_PUBLIC_SUPABASE_ANON_KEY=$NEXT_PUBLIC_SUPABASE_ANON_KEY \
    NEXT_PUBLIC_SOLANA_RPC_URL=$NEXT_PUBLIC_SOLANA_RPC_URL
# rewrites() results are serialized into the standalone build at `next build`
# time — API_INTERNAL_URL must be a build arg, a runtime env never reaches it.
ARG API_INTERNAL_URL=http://api:8080
ENV API_INTERNAL_URL=$API_INTERNAL_URL
RUN npm run build

FROM node:22.23.3-alpine@sha256:0a7108bf6c7bf5de370ffb1a3ed6be93d405b43ff159f681a8d18c0e2bc2e402 AS runner
WORKDIR /srv/web
# PORT=80 matches the compose healthcheck (wget on :80); HOSTNAME binds beyond loopback.
ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    PORT=80 \
    HOSTNAME=0.0.0.0
RUN addgroup --system --gid 1001 nodejs \
    && adduser --system --uid 1001 --ingroup nodejs nextjs
COPY --from=build --chown=nextjs:nodejs /srv/web/.next/standalone ./
COPY --from=build --chown=nextjs:nodejs /srv/web/.next/static ./.next/static
COPY --from=build --chown=nextjs:nodejs /srv/web/public ./public
USER nextjs
EXPOSE 80
CMD ["node", "server.js"]
