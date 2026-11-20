"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { useEffect, useState } from "react";
import { ThemeToggle, useAuth } from "@/components/AuthProvider";
import { ErrorBox } from "@/components/Feedback";
import { IconCheck } from "@/components/Icons";
import { Logo } from "@/components/Logo";
import { authApi } from "@/lib/api";

export default function LoginPage() {
  const router = useRouter();
  const params = useSearchParams();
  const { user, setUser } = useAuth();
  const [userId, setUserId] = useState("");
  const [password, setPassword] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<unknown>(null);

  // Only same-site relative paths (no open redirect)
  const raw = params.get("next") ?? "/";
  const next = raw.startsWith("/") && !raw.startsWith("//") && raw !== "/login" ? raw : "/";

  useEffect(() => {
    if (user) router.replace(user.mustChangePassword ? "/password" : next);
  }, [user, next, router]);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (submitting) return;
    setSubmitting(true);
    setError(null);
    try {
      setUser(await authApi.login(userId.trim(), password));
    } catch (err) {
      setError(err);
      setPassword("");
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="auth-layout">
      <section className="auth-hero">
        <div className="brand" style={{ color: "#fff", padding: 0 }}>
          <Logo size={44} />
          <span>
            永劫銀行
            <small style={{ color: "#c7d2fe" }}>ONLINE BANKING</small>
          </span>
        </div>
        <div style={{ position: "relative", zIndex: 1 }}>
          <h2>
            あなたのお金を、
            <br />
            いつでも、安全に。
          </h2>
          <p>残高・取引履歴の確認から振込・振替まで。窓口・役席の業務もひとつの画面で。</p>
          <ul className="auth-points">
            <li><IconCheck size={18} /> PBKDF2 によるパスワード保護とログインロック</li>
            <li><IconCheck size={18} /> 改ざんを検知できる監査ログ</li>
            <li><IconCheck size={18} /> 二重送信を防ぐ冪等な取引処理</li>
          </ul>
        </div>
        <p style={{ fontSize: "0.78rem", color: "#a5b4fc" }}>デモ用サンプル（架空データ）。本番利用不可。</p>
      </section>

      <section className="auth-panel">
        <div className="auth-card">
          <div style={{ display: "flex", justifyContent: "flex-end", marginBottom: 24 }}>
            <ThemeToggle />
          </div>
          <p className="eyebrow">Welcome back</p>
          <h1>ログイン</h1>
          <p className="muted" style={{ marginTop: 0, marginBottom: 24 }}>利用者IDとパスワードを入力してください。</p>
          <ErrorBox error={error} />
          <form onSubmit={submit} style={{ maxWidth: "none" }}>
            <label>
              利用者ID
              <input required autoComplete="username" autoCapitalize="none" maxLength={30} value={userId}
                onChange={(e) => setUserId(e.target.value)} autoFocus />
            </label>
            <label>
              パスワード
              <input required type="password" autoComplete="current-password" maxLength={128} value={password}
                onChange={(e) => setPassword(e.target.value)} />
            </label>
            <button className="btn" type="submit" disabled={submitting} style={{ width: "100%" }}>
              {submitting ? "確認中…" : "ログイン"}
            </button>
            <p className="hint" style={{ margin: 0 }}>
              5回続けて失敗すると15分間ロックされます。デモ用の利用者は README の「ログインとデモユーザー」を参照してください。
            </p>
          </form>
        </div>
      </section>
    </div>
  );
}
