# 永劫銀行の AI アシスタント（Claude・Anthropic の公式 SDK）
FROM node:22-alpine
WORKDIR /app
ENV NODE_ENV=production PORT=8090
COPY package.json package-lock.json ./
RUN npm ci --omit=dev --no-audit --no-fund
COPY src ./src
USER node
EXPOSE 8090
HEALTHCHECK --interval=10s --timeout=3s --retries=5 CMD wget -q -O /dev/null http://127.0.0.1:8090/live || exit 1
CMD ["node", "src/server.mjs"]
