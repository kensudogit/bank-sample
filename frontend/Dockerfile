# ---- Build ----
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
# Rewrites to the Liberty API are resolved at build time
ARG BANK_API_ORIGIN=http://liberty:9080
ENV BANK_API_ORIGIN=${BANK_API_ORIGIN} NEXT_TELEMETRY_DISABLED=1
RUN npm run build

# ---- Runtime (standalone output) ----
FROM node:22-alpine
WORKDIR /app
ENV NODE_ENV=production NEXT_TELEMETRY_DISABLED=1 PORT=3000 HOSTNAME=0.0.0.0
COPY --from=build --chown=node:node /app/.next/standalone ./
COPY --from=build --chown=node:node /app/.next/static ./.next/static
USER node
EXPOSE 3000
CMD ["node", "server.js"]
