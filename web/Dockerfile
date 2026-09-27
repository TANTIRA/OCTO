# OCTO web image: infra/docker-compose.yml pulls ${REGISTRY_URL}/octo-web:${WEB_IMAGE_TAG};
# deploy/dokploy.compose.yml builds it on the Dokploy host. Build context is web/.
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
ENV NEXT_TELEMETRY_DISABLED=1
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
