# MuleSoft 互換コンテナ（依存パッケージなし。Node.js 標準機能だけで動く）
FROM node:22-alpine
WORKDIR /app
ENV NODE_ENV=production PORT=8081
COPY package.json ./
COPY src ./src
USER node
EXPOSE 8081
HEALTHCHECK --interval=10s --timeout=3s --retries=5 CMD wget -q -O /dev/null http://127.0.0.1:8081/api/v1/health || exit 1
CMD ["node", "src/server.mjs"]
