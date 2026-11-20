"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { RequireRole } from "@/components/AuthProvider";
import { ErrorBox } from "@/components/Feedback";
import { ApiError, bankApi, newIdempotencyKey, type RegisterCustomerRequest } from "@/lib/api";
import { idDocLabel, normalizeDigits } from "@/lib/format";
import type { IdDocType } from "@/lib/types";

type Form = Omit<RegisterCustomerRequest, "idempotencyKey">;

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

function NewCustomerForm() {
  const router = useRouter();
  const [form, setForm] = useState<Form>(EMPTY);
  const [key, setKey] = useState(newIdempotencyKey);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<unknown>(null);

  const set = (field: keyof Form) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
    setForm({ ...form, [field]: e.target.value });

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (submitting) return;
    setSubmitting(true);
    setError(null);
    try {
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
