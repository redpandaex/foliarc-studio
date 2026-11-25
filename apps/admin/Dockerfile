# syntax=docker/dockerfile:1.7

# ==============================================================================
# Stage 1: Install dependencies & build Next.js Admin standalone bundle (:3001)
# ==============================================================================
FROM node:24-alpine AS builder

RUN npm install -g pnpm@12.14.0

WORKDIR /app

COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN --mount=type=cache,id=pnpm,target=/root/.local/share/pnpm/store \
    pnpm install --frozen-lockfile

COPY . .

ARG NEXT_PUBLIC_ADMIN_API_BASE_URL=http://localhost:8081
ENV NEXT_PUBLIC_ADMIN_API_BASE_URL=$NEXT_PUBLIC_ADMIN_API_BASE_URL \
    NEXT_TELEMETRY_DISABLED=1

RUN pnpm build

# ==============================================================================
# Stage 2: Minimal non-root Next.js Admin standalone runner
# ==============================================================================
FROM node:24-alpine AS runner

WORKDIR /app

ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    PORT=3001 \
    HOSTNAME=0.0.0.0

RUN addgroup --system --gid 1001 nodejs && \
    adduser --system --uid 1001 nextjs

COPY --from=builder /app/public ./public
COPY --from=builder --chown=nextjs:nodejs /app/.next/standalone ./
COPY --from=builder --chown=nextjs:nodejs /app/.next/static ./.next/static

USER nextjs

EXPOSE 3001

HEALTHCHECK --interval=15s --timeout=5s --start-period=20s --retries=3 \
    CMD wget -qO- http://127.0.0.1:3001/api/health >/dev/null || exit 1

CMD ["node", "server.js"]
