# Oracle Database 23ai Free（Railway 用）。初回起動時に init/ の SQL とスクリプトで BANK スキーマを作る。
# docker compose では使わない（compose は gvenzl/oracle-free に db/init をそのままマウントする）
FROM gvenzl/oracle-free:23-slim
ENV NLS_LANG=AMERICAN_AMERICA.AL32UTF8
COPY --chown=oracle:oinstall init/ /container-entrypoint-initdb.d/
COPY --chown=oracle:oinstall --chmod=755 railway-entrypoint.sh /opt/oracle/railway-entrypoint.sh
# Railway のボリュームの所有者を直してから起動する
ENTRYPOINT ["/opt/oracle/railway-entrypoint.sh"]
