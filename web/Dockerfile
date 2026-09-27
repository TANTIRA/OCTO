# OCTO web image: infra/docker-compose.yml pulls ${REGISTRY_URL}/octo-web:${WEB_IMAGE_TAG}.
# Build from the repo root:  docker build -t "$REGISTRY_URL/octo-web:$WEB_IMAGE_TAG" -f web/Dockerfile web
FROM node:22-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci

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
