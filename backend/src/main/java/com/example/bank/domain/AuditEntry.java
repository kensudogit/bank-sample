package com.example.bank.domain;

/** 監査ログの 1 行。 */
public final class AuditEntry {
    // 各行は直前行のハッシュ（prevHash）を含めてハッシュ化され、改ざんや削除を検出できるチェーンになる。
    private final long auditId;
    private final String occurredAt;
    private final String userId;
    private final String userRole;
    private final String action;
    private final String target;
    private final String outcome;
    private final String detail;
    private final String clientIp;
    private final String prevHash;
    private final String rowHash;
    private String requestKey;

    /**
     * @param auditId    監査 ID（採番順 = チェーンの順序）
     * @param occurredAt 発生日時
     * @param userId     操作者の利用者 ID
     * @param userRole   操作者のロール
     * @param action     操作名
     * @param target     操作対象（口座番号・顧客番号など）
     * @param outcome    結果（SUCCESS / FAILURE / DENIED）
     * @param detail     詳細
     * @param clientIp   接続元 IP アドレス
     * @param prevHash   直前行の rowHash
     * @param rowHash    この行のハッシュ
     */
    public AuditEntry(long auditId, String occurredAt, String userId, String userRole, String action, String target,
                      String outcome, String detail, String clientIp, String prevHash, String rowHash) {
        this.auditId = auditId;
        this.occurredAt = occurredAt;
        this.userId = userId;
        this.userRole = userRole;
        this.action = action;
        this.target = target;
        this.outcome = outcome;
        this.detail = detail;
        this.clientIp = clientIp;
        this.prevHash = prevHash;
        this.rowHash = rowHash;
    }

    /** @return 監査 ID */
    public long getAuditId() { return auditId; }
    /** @return 発生日時 */
    public String getOccurredAt() { return occurredAt; }
    /** @return 操作者の利用者 ID */
    public String getUserId() { return userId; }
    /** @return 操作者のロール */
    public String getUserRole() { return userRole; }
    /** @return 操作名 */
    public String getAction() { return action; }
    /** @return 操作対象 */
    public String getTarget() { return target; }
    /** SUCCESS / FAILURE / DENIED */
    public String getOutcome() { return outcome; }
    /** @return 詳細 */
    public String getDetail() { return detail; }
    /** @return 接続元 IP アドレス */
    public String getClientIp() { return clientIp; }
    /** @return 直前行のハッシュ（チェーンの前方参照） */
    public String getPrevHash() { return prevHash; }
    /** @return この行のハッシュ */
    public String getRowHash() { return rowHash; }
    /** 操作の冪等キー（明細との対応付け） */
    public String getRequestKey() { return requestKey; }
    /**
     * 冪等キーを設定する（読み込み後に後付けするため、このフィールドだけ非 final）。
     *
     * @param key 冪等キー
     * @return このインスタンス
     */
    public AuditEntry withRequestKey(String key) {
        this.requestKey = key;
        return this;
    }

    /** ハッシュ対象の正規化文字列（区切りは改行、null は空文字）。 */
    public static String canonical(long auditId, String occurredAt, String userId, String userRole, String action,
                                   String target, String outcome, String detail, String clientIp, String prevHash) {
        return canonical(auditId, occurredAt, userId, userRole, action, target, outcome, detail, clientIp, prevHash, null);
    }

    /** request_key は値がある行だけハッシュに含める（この列の追加前の行も検証できるように）。 */
    public static String canonical(long auditId, String occurredAt, String userId, String userRole, String action,
                                   String target, String outcome, String detail, String clientIp, String prevHash,
                                   String requestKey) {
        StringBuilder sb = new StringBuilder();
        for (Object v : new Object[]{auditId, occurredAt, userId, userRole, action, target, outcome, detail, clientIp,
                prevHash}) {
            // 区切り文字（改行）とエスケープ文字をエスケープし、値の境界をずらした改ざんで同じハッシュにならないようにする
            sb.append(v == null ? "" : v.toString().replace("\\", "\\\\").replace("\n", "\\n")).append('\n');
        }
        // 列追加前の行は requestKey が null なので、何も足さずに旧形式と同じ文字列になる
        if (requestKey != null) sb.append("key:").append(requestKey).append('\n');
        return sb.toString();
    }
}
