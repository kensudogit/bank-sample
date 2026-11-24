# MuleSoft 互換コンテナ（依存パッケージなし。Node.js 標準機能だけで動く）
# 8443：業務 API（mTLS。Kong のクライアント証明書だけを受け付ける）/ 8081：死活確認（/live）と指標（/metrics）だけ
FROM node:22-alpine
WORKDIR /app
ENV NODE_ENV=production PORT=8081 TLS_PORT=8443
COPY package.json ./
COPY src ./src
USER node
EXPOSE 8443 8081
HEALTHCHECK --interval=10s --timeout=3s --retries=5 CMD wget -q -O /dev/null http://127.0.0.1:8081/live || exit 1
CMD ["node", "src/server.mjs"]
