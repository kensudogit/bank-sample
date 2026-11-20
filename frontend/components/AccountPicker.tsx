"use client";

import { useEffect } from "react";
import { useAuth } from "@/components/AuthProvider";
import { useAccounts } from "@/lib/api";
import { accountTypeLabel, formatMoney, normalizeAmount } from "@/lib/format";
import type { Account } from "@/lib/types";

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
  return (
    <label>
      {label}
      <input inputMode="numeric" required maxLength={7} value={value} placeholder="1000001"
        onChange={(e) => onChange(normalizeAmount(e.target.value))} />
    </label>
  );
}
