# OAuth 2.0 トークン発行サーバー（依存パッケージなし）
FROM node:22-alpine
WORKDIR /app
ENV NODE_ENV=production PORT=8080
COPY package.json ./
COPY src ./src
USER node
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --retries=5 CMD wget -q -O /dev/null http://127.0.0.1:8080/live || exit 1
CMD ["node", "src/server.mjs"]
