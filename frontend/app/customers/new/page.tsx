"use client";

// 口座開設の申込画面（/customers/new、行員専用）。顧客情報と本人確認書類を受け付けて顧客を登録する。

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { RequireRole } from "@/components/AuthProvider";
import { ErrorBox } from "@/components/Feedback";
import { ApiError, bankApi, newIdempotencyKey, type RegisterCustomerRequest } from "@/lib/api";
import { idDocLabel, normalizeDigits } from "@/lib/format";
import type { IdDocType } from "@/lib/types";

/** 入力フォームの状態（冪等キーはフォームとは別に管理する） */
type Form = Omit<RegisterCustomerRequest, "idempotencyKey">;

/** 初期値（個人・運転免許証） */
const EMPTY: Form = {
  customerType: "INDIVIDUAL",
  name: "",
  nameKana: "",
  birthDate: "",
  postalCode: "",
  address: "",
  phone: "",
  idDocType: "DRIVER_LICENSE",
  idDocNumber: "",
};

/** 口座開設の申込: 顧客情報と本人確認書類を受け付ける（本人確認は次の画面で担当者が行う）。 */
export default function NewCustomerPage() {
  return (
    <RequireRole roles={["TELLER", "SUPERVISOR"]}>
      <NewCustomerForm />
    </RequireRole>
  );
}

/** 申込フォーム本体。登録に成功したら顧客詳細画面へ移り、そこで本人確認を行う。 */
function NewCustomerForm() {
  const router = useRouter();
  const [form, setForm] = useState<Form>(EMPTY);
  // 冪等キー：二重クリックや通信の再送で同じ顧客が二重登録されないようにする
  const [key, setKey] = useState(newIdempotencyKey);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<unknown>(null);

  // 各入力欄の onChange をまとめて作るヘルパー
  const set = (field: keyof Form) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
    setForm({ ...form, [field]: e.target.value });

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (submitting) return;
    setSubmitting(true);
    setError(null);
    try {
      // 全角数字・ハイフンを半角に揃え、電話番号はハイフンを除いて送る
      const customer = await bankApi.registerCustomer({
        ...form,
        name: form.name.trim(),
        nameKana: form.nameKana.trim(),
        postalCode: normalizeDigits(form.postalCode),
        phone: normalizeDigits(form.phone).replace(/-/g, ""),
        idDocNumber: normalizeDigits(form.idDocNumber).toUpperCase(),
        idempotencyKey: key,
      });
      router.push(`/customers/${customer.customerId}`);
    } catch (err) {
      setError(err);
      // 同じキーが処理済み扱いになった場合は、再申込できるようキーを作り直す
      if (err instanceof ApiError && err.code === "DUPLICATE_REQUEST") setKey(newIdempotencyKey());
      setSubmitting(false);
    }
  };

  return (
    <>
      <Link href="/customers" className="back-link">← 顧客一覧</Link>
      <h1>口座開設の申込</h1>
      <ErrorBox error={error} />
      <section className="card">
        <form className="wide" onSubmit={submit}>
          {/* 個人・法人の切り替え。法人の場合は項目名を商号・設立日に変える */}
          <div className="tabs" role="tablist">
            {(["INDIVIDUAL", "CORPORATE"] as const).map((t) => (
              <button key={t} type="button" role="tab" className="tab" aria-selected={form.customerType === t}
                onClick={() => setForm({ ...form, customerType: t })}>
                {t === "INDIVIDUAL" ? "個人" : "法人"}
              </button>
            ))}
          </div>
          <div className="form-grid">
            <label>
              {form.customerType === "CORPORATE" ? "商号" : "氏名"}
              <input required maxLength={50} autoComplete="off" value={form.name} onChange={set("name")}
                placeholder="山田 太郎" />
            </label>
            <label>
              フリガナ
              <input required maxLength={50} autoComplete="off" value={form.nameKana} onChange={set("nameKana")}
                placeholder="ヤマダ タロウ" />
              <span className="hint">全角カタカナ</span>
            </label>
            <label>
              {form.customerType === "CORPORATE" ? "設立日" : "生年月日"}
              <input type="date" required value={form.birthDate} onChange={set("birthDate")} />
              <span className="hint">{form.customerType === "CORPORATE" ? "登記事項証明書で確認" : "18歳以上の方"}</span>
            </label>
            <label>
              電話番号
              <input required inputMode="tel" maxLength={13} autoComplete="off" value={form.phone}
                onChange={set("phone")} placeholder="090-1234-5678" />
            </label>
            <label>
              郵便番号
              <input required inputMode="numeric" maxLength={8} autoComplete="off" value={form.postalCode}
                onChange={set("postalCode")} placeholder="100-0001" />
            </label>
          </div>
          <label>
            住所
            <input required maxLength={200} autoComplete="off" value={form.address} onChange={set("address")}
              placeholder="東京都千代田区千代田1-1" />
          </label>
          <div className="form-grid">
            <label>
              本人確認書類
              <select value={form.idDocType} onChange={set("idDocType")}>
                {(Object.keys(idDocLabel) as IdDocType[]).map((t) => (
                  <option key={t} value={t}>
                    {idDocLabel[t]}
                  </option>
                ))}
              </select>
            </label>
            <label>
              書類の番号
              <input required maxLength={20} autoComplete="off" value={form.idDocNumber}
                onChange={set("idDocNumber")} placeholder="英数字8〜20文字" />
              <span className="hint">保存されるのは下4桁のみです</span>
            </label>
          </div>
          <div className="actions">
            <button className="btn" type="submit" disabled={submitting}>
              {submitting ? "送信中…" : "申込を登録する"}
            </button>
          </div>
        </form>
      </section>
    </>
  );
}
