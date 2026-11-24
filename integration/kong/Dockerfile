# Kong Gateway（OSS・DB-less）。宣言的設定のひな形・起動時に値を埋め込むスクリプト・独自プラグイン（トークンの iss・aud・roles の検証）を同梱する
FROM kong:3.9.1
COPY plugins/bank-token-claims /usr/local/share/lua/5.1/kong/plugins/bank-token-claims
COPY kong.yml.tmpl render-config.sh /kong/
# 標準のプラグインに加えて独自プラグインを有効にする
ENV KONG_PLUGINS=bundled,bank-token-claims
ENTRYPOINT ["/bin/sh", "/kong/render-config.sh"]
