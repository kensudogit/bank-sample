import { ApiError } from "@/lib/api";

export function ErrorBox({ error }: { error: unknown }) {
  if (!error) return null;
  const message = error instanceof ApiError ? error.message : "予期しないエラーが発生しました。";
  const fieldErrors = error instanceof ApiError ? Object.values(error.fieldErrors).flat() : [];
  return (
    <div className="alert alert-error" role="alert">
      <p>{message}</p>
      {fieldErrors.length > 0 && (
        <ul>
          {fieldErrors.map((m, i) => (
            <li key={i}>{m}</li>
          ))}
        </ul>
      )}
    </div>
  );
}

export function SuccessBox({ children }: { children: React.ReactNode }) {
  return (
    <div className="alert alert-success" role="status">
      {children}
    </div>
  );
}

export function Loading() {
  return <p className="muted">読み込み中…</p>;
}
