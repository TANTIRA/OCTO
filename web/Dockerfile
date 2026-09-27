# OCTO web image: infra/docker-compose.yml pulls ${REGISTRY_URL}/octo-web:${WEB_IMAGE_TAG};
# deploy/dokploy.compose.yml builds it on the Dokploy host. Build context is web/.
# OCTO web image: infra/docker-compose.yml pulls ${REGISTRY_URL}/octo-web:${WEB_IMAGE_TAG}.
# Build from the repo root:  docker build -t "$REGISTRY_URL/octo-web:$WEB_IMAGE_TAG" -f web/Dockerfile web
FROM node:22-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
# Build from the repo root:  docker build -t "$REGISTRY_URL/octo-web:$WEB_IMAGE_TAG" web/
#
# next.config.ts sets output: "standalone" — `next build` emits .next/standalone,
# a pruned server bundle; the runtime stage carries only that, static assets, and
# public files. No dev dependencies, no sources.

FROM node:22-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

FROM node:22-alpine AS build
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY . .
RUN npm run build

FROM node:22-alpine
WORKDIR /app
ENV NODE_ENV=production PORT=80 HOSTNAME=0.0.0.0
RUN addgroup -S octo && adduser -S octo -G octo
COPY --from=build /app/package.json ./
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/.next ./.next
USER octo
EXPOSE 80
CMD ["npm", "start"]
ENV NEXT_TELEMETRY_DISABLED=1
# NEXT_PUBLIC_* is inlined into the client bundle at build time — the anon key
# is publishable by design (security lives server-side), so baking it in is safe.
ARG NEXT_PUBLIC_SUPABASE_URL
ARG NEXT_PUBLIC_SUPABASE_ANON_KEY
ENV NEXT_PUBLIC_SUPABASE_URL=$NEXT_PUBLIC_SUPABASE_URL \
    NEXT_PUBLIC_SUPABASE_ANON_KEY=$NEXT_PUBLIC_SUPABASE_ANON_KEY
# rewrites() results are serialized into the standalone build at `next build`
# time — API_INTERNAL_URL must be a build arg, a runtime env never reaches it.
ARG API_INTERNAL_URL=http://api:8080
ENV API_INTERNAL_URL=$API_INTERNAL_URL
RUN npm run build

FROM node:22-alpine AS runner
WORKDIR /app
# PORT=80 matches the compose healthcheck (wget on :80); HOSTNAME binds beyond loopback.
ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    PORT=80 \
    HOSTNAME=0.0.0.0
RUN addgroup --system --gid 1001 nodejs \
    && adduser --system --uid 1001 --ingroup nodejs nextjs
COPY --from=build --chown=nextjs:nodejs /app/.next/standalone ./
COPY --from=build --chown=nextjs:nodejs /app/.next/static ./.next/static
COPY --from=build --chown=nextjs:nodejs /app/public ./public
USER nextjs
EXPOSE 80
CMD ["node", "server.js"]
