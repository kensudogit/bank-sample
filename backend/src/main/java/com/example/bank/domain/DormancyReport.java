package com.example.bank.domain;

import java.util.List;

/** 休眠預金管理の対象一覧（基準日時点）と、実行時の処理件数。 */
public final class DormancyReport {
    private final String asOf;
    private final List<Account> noticeTargets;
    private final List<Account> dormancyTargets;
    private final List<Account> dormantAccounts;
    private final int noticed;
    private final int madeDormant;

    public DormancyReport(String asOf, List<Account> noticeTargets, List<Account> dormancyTargets,
                          List<Account> dormantAccounts, int noticed, int madeDormant) {
        this.asOf = asOf;
        this.noticeTargets = noticeTargets;
        this.dormancyTargets = dormancyTargets;
        this.dormantAccounts = dormantAccounts;
        this.noticed = noticed;
        this.madeDormant = madeDormant;
    }

    public String getAsOf() { return asOf; }
    /** 最終異動から 9 年以上・残高 1 万円以上・未通知（休眠前の通知対象） */
    public List<Account> getNoticeTargets() { return noticeTargets; }
    /** 最終異動から 10 年以上で休眠化の条件を満たす口座 */
    public List<Account> getDormancyTargets() { return dormancyTargets; }
    /** 休眠中の口座 */
    public List<Account> getDormantAccounts() { return dormantAccounts; }
    /** 実行時: 通知を記録した件数（照会時は 0） */
    public int getNoticed() { return noticed; }
    /** 実行時: 休眠にした件数（照会時は 0） */
    public int getMadeDormant() { return madeDormant; }
}
