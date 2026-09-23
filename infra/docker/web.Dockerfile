# syntax=docker/dockerfile:1
#
# The Next.js web app (cashier lane and back office, with its BFF). Build context is frontend/web.

FROM node:24-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund

FROM node:24-alpine AS build
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY . .
ENV NEXT_TELEMETRY_DISABLED=1
RUN npm run build

FROM node:24-alpine AS runtime
# Non-root, like the services.
RUN addgroup -S pos && adduser -S -G pos pos
WORKDIR /app
ENV NODE_ENV=production NEXT_TELEMETRY_DISABLED=1 PORT=3000 HOSTNAME=0.0.0.0
COPY --from=build --chown=pos:pos /app/.next/standalone ./
COPY --from=build --chown=pos:pos /app/.next/static ./.next/static
COPY --from=build --chown=pos:pos /app/public ./public
USER pos
EXPOSE 3000
CMD ["node", "server.js"]
