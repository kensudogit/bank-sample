"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { useAuth } from "@/components/AuthProvider";
import { ErrorBox, SuccessBox } from "@/components/Feedback";
import { ApiError, authApi } from "@/lib/api";

export default function PasswordPage() {
  const router = useRouter();
  const { user, setUser } = useAuth();
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [confirm, setConfirm] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [done, setDone] = useState(false);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (submitting) return;
    setError(null);
    if (next !== confirm) {
      setError(new ApiError(400, "VALIDATION_ERROR", "新しいパスワード（確認）が一致しません。"));
      return;
    }
    setSubmitting(true);
    try {
      const updated = await authApi.changePassword(current, next);
      setDone(true);
      setCurrent("");
      setNext("");
      setConfirm("");
      setUser(updated);
      router.replace("/");
    } catch (err) {
      setError(err);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <>
      <h1>パスワード変更</h1>
      {user?.mustChangePassword && (
        <div className="alert alert-error">初期パスワードのため、ご利用の前に新しいパスワードを設定してください。</div>
      )}
      <ErrorBox error={error} />
      {done && <SuccessBox>パスワードを変更しました。</SuccessBox>}
      <section className="card">
        <form onSubmit={submit}>
          <label>
            現在のパスワード
            <input required type="password" autoComplete="current-password" maxLength={128} value={current}
              onChange={(e) => setCurrent(e.target.value)} />
          </label>
          <label>
            新しいパスワード
            <input required type="password" autoComplete="new-password" minLength={10} maxLength={64} value={next}
              onChange={(e) => setNext(e.target.value)} />
            <span className="hint">10〜64文字・英字と数字を含む（利用者IDや現在のパスワードとは別のもの）</span>
          </label>
          <label>
            新しいパスワード（確認）
            <input required type="password" autoComplete="new-password" maxLength={64} value={confirm}
              onChange={(e) => setConfirm(e.target.value)} />
          </label>
          <div className="actions">
            <button className="btn" type="submit" disabled={submitting}>
              {submitting ? "変更中…" : "変更する"}
            </button>
          </div>
        </form>
      </section>
    </>
  );
}
