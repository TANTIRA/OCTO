# OCTO web image: infra/docker-compose.yml pulls ${REGISTRY_URL}/octo-web:${WEB_IMAGE_TAG}.
# Build from the repo root:  docker build -t "$REGISTRY_URL/octo-web:$WEB_IMAGE_TAG" web/
#
# Three stages so the runtime image carries the standalone server only — no dev
# dependencies, no sources. `next build` emits `.next/standalone`, a pruned server
# bundle; it needs only static assets and public files copied alongside.

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
