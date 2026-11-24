// 画面テスト用の描画ヘルパーとテストデータ。
// ページを本物の AppShell（ログイン状態の管理）で包み、/me の応答で利用者の役割を切り替えて描画する。

import { act, render, screen, waitFor, within, type RenderResult } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ReactElement } from "react";
import { expect } from "vitest";
import { AppShell } from "@/components/AuthProvider";
import type { Account, LoginUser } from "@/lib/types";
import { callsTo, mockApi } from "./api";
import { setNav } from "./nav";

/** お客さま（CU0001）のログイン利用者 */
export const customerUser: LoginUser = {
  userId: "cust01",
  displayName: "山田 太郎",
  role: "CUSTOMER",
  customerId: "CU0001",
  mustChangePassword: false,
  customer: true,
  staff: false,
};

/** 窓口担当（テラー） */
export const tellerUser: LoginUser = {
  userId: "teller01",
  displayName: "窓口 花子",
  role: "TELLER",
  customerId: null,
  mustChangePassword: false,
  customer: false,
  staff: true,
};

/** 役席（スーパーバイザー） */
export const supervisorUser: LoginUser = {
  userId: "super01",
  displayName: "役席 次郎",
  role: "SUPERVISOR",
  customerId: null,
  mustChangePassword: false,
  customer: false,
  staff: true,
};

/** 口座のテストデータ（必要な項目だけ上書きして使う） */
export function account(over: Partial<Account> = {}): Account {
  return {
    accountNo: "1000001",
    customerId: "CU0001",
    customerName: "山田 太郎",
    accountType: "ORDINARY",
    balance: 1_250_000,
    status: "ACTIVE",
    updatedAt: "2026-11-20T10:00:00",
    openedAt: "2024-04-01T09:00:00",
    closedAt: null,
    lastActivityAt: "2026-11-20T10:00:00",
    dormancyNotifiedAt: null,
    dormantAt: null,
    currency: "JPY",
    overdraftLimit: 0,
    availableBalance: 1_250_000,
    ...over,
  };
}

/** 口座一覧 API（/accounts）の応答 */
export function accountPage(items: Account[]) {
  return {
    items,
    page: 1,
    size: 50,
    total: items.length,
    hasNext: false,
    totalBalance: items.reduce((s, a) => s + a.balance, 0),
  };
}

export interface RenderPageOptions {
  /** ログイン利用者（null は未ログイン） */
  user?: LoginUser | null;
  /** 現在のパス */
  path?: string;
  /** 動的ルートのパラメータ（[accountNo] など） */
  params?: Record<string, string>;
  /** クエリ文字列 */
  search?: string;
  /** 利用手順パネルを開いた状態で描画する */
  guideOpen?: boolean;
}

/**
 * ページを AppShell で包んで描画し、ログイン確認（/me）が終わってページ本体が出るまで待つ。
 * 戻り値の user は userEvent のインスタンス。
 */
export async function renderPage(ui: ReactElement, opts: RenderPageOptions = {}) {
  const { user = supervisorUser, path = "/", params = {}, search = "" } = opts;
  setNav(path, params, search);
  mockApi({ "/me": user });
  // 利用手順パネルは初回自動で開くため、ページのテストでは閉じた状態にしておく（パネル自体は専用テストで確認）
  if (!opts.guideOpen) localStorage.setItem("bank-sample.guide.open", "0");
  const ue = userEvent.setup();
  const result: RenderResult = render(<AppShell>{ui}</AppShell>);
  // ログイン確認（/me）の応答を待ち、利用者名がサイドバーに出る（＝ページ本体を描画してよい）まで待つ
  await waitFor(() => expect(callsTo("/me").length).toBeGreaterThan(0));
  if (user && !user.mustChangePassword && path !== "/login") await screen.findAllByText(user.displayName);
  await act(async () => {
    await new Promise((r) => setTimeout(r, 0));
  });
  return { ...result, user: ue };
}

/** AppShell の内側で部品だけを描画する（useAuth を使う部品用）。 */
export async function renderWithAuth(ui: ReactElement, user: LoginUser | null = supervisorUser, path = "/") {
  return renderPage(ui, { user, path });
}

/** ページ本体（サイドバー・利用手順を除く <main>）に限定したクエリ */
export function main() {
  return within(document.querySelector("main") as HTMLElement);
}
