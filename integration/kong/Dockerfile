# Kong Gateway（OSS・DB-less）。宣言的設定のひな形と、起動時に鍵・転送先を埋め込むスクリプトを同梱する
FROM kong:3.9.1
COPY kong.yml.tmpl render-config.sh /kong/
ENTRYPOINT ["/bin/sh", "/kong/render-config.sh"]
