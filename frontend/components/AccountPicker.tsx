"use client";

// 口座の選択部品。ログイン利用者が顧客か行員かで、プルダウン選択と口座番号入力を切り替える。

import { useEffect } from "react";
import { useAuth } from "@/components/AuthProvider";
import { useAccounts } from "@/lib/api";
import { accountTypeLabel, formatMoney, normalizeAmount } from "@/lib/format";
import type { Account } from "@/lib/types";

// 既定では、利用可能な円建ての口座（定期預金を除く）だけを選択肢にする
const DEFAULT_FILTER = (a: Account) => a.status === "ACTIVE" && a.currency === "JPY" && a.accountType !== "TIME";

/**
 * 口座の選択: お客さまは本人名義の口座から選び、行員は口座番号を入力する。
 *
 * @param filter 選べる口座の条件（お客さまの選択肢のみ）
 */
export function AccountPicker({
  label,
  value,
  onChange,
  filter = DEFAULT_FILTER,
}: {
  label: string;
  value: string;
  onChange: (accountNo: string) => void;
  filter?: (a: Account) => boolean;
}) {
  const { user } = useAuth();
  const { data } = useAccounts({ size: 200 });
  const isCustomer = user?.customer ?? false;
  const options = isCustomer ? (data?.items.filter(filter) ?? []) : [];
  const first = options[0]?.accountNo ?? "";

  // Customers: preselect the first eligible account once the list is loaded
  useEffect(() => {
    if (isCustomer && !value && first) onChange(first);
  }, [isCustomer, value, first, onChange]);

  // 顧客：本人名義の口座だけをプルダウンで選ばせる（他人の口座番号を入力させない）
  if (isCustomer) {
    return (
      <label>
        {label}
        <select value={value} onChange={(e) => onChange(e.target.value)} required>
          {options.length === 0 && <option value="">選べる口座がありません</option>}
          {options.map((a) => (
            <option key={a.accountNo} value={a.accountNo}>
              {a.accountNo} {accountTypeLabel[a.accountType]}（残高 {formatMoney(a.balance, a.currency)}）
            </option>
          ))}
        </select>
      </label>
    );
  }
  // 行員：任意の口座番号を入力。全角数字で入力されても半角に正規化する
  return (
    <label>
      {label}
      <input inputMode="numeric" required maxLength={7} value={value} placeholder="1000001"
        onChange={(e) => onChange(normalizeAmount(e.target.value))} />
    </label>
  );
}
