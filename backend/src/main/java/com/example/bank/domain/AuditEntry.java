package com.example.bank.domain;

/** 監査ログの 1 行。 */
public final class AuditEntry {
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

    public long getAuditId() { return auditId; }
    public String getOccurredAt() { return occurredAt; }
    public String getUserId() { return userId; }
    public String getUserRole() { return userRole; }
    public String getAction() { return action; }
    public String getTarget() { return target; }
    /** SUCCESS / FAILURE / DENIED */
    public String getOutcome() { return outcome; }
    public String getDetail() { return detail; }
    public String getClientIp() { return clientIp; }
    public String getPrevHash() { return prevHash; }
    public String getRowHash() { return rowHash; }
    /** 操作の冪等キー（明細との対応付け） */
    public String getRequestKey() { return requestKey; }
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
            sb.append(v == null ? "" : v.toString().replace("\\", "\\\\").replace("\n", "\\n")).append('\n');
        }
        if (requestKey != null) sb.append("key:").append(requestKey).append('\n');
        return sb.toString();
    }
}
