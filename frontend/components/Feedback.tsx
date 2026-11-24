// 画面共通のフィードバック表示部品：エラー表示・成功表示・読み込み中のスケルトン。

import { IconAlert, IconCheck } from "@/components/Icons";
import { ApiError } from "@/lib/api";

/**
 * API エラーのメッセージと項目別エラーを一覧表示する。error が空なら何も表示しない。
 * ApiError 以外（想定外の例外）は内部情報を見せず、汎用メッセージにする。
 */
export function ErrorBox({ error }: { error: unknown }) {
  if (!error) return null;
  const message = error instanceof ApiError ? error.message : "予期しないエラーが発生しました。";
  const fieldErrors = error instanceof ApiError ? Object.values(error.fieldErrors).flat() : [];
  return (
    <div className="alert alert-error" role="alert">
      <IconAlert size={18} />
      <div>
        <p>{message}</p>
        {fieldErrors.length > 0 && (
          <ul>
            {fieldErrors.map((m, i) => (
              <li key={i}>{m}</li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}

/** 処理成功のメッセージを表示する（role="status" でスクリーンリーダーにも通知）。 */
export function SuccessBox({ children }: { children: React.ReactNode }) {
  return (
    <div className="alert alert-success" role="status">
      <IconCheck size={18} />
      <div>{children}</div>
    </div>
  );
}

/** Skeleton placeholder while data loads (announced to screen readers). */
export function Loading() {
  return (
    <div className="skeleton" role="status" aria-label="読み込み中">
      <div />
      <div />
      <div style={{ width: "70%" }} />
    </div>
  );
}
