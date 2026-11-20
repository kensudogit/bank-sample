import { IconAlert, IconCheck } from "@/components/Icons";
import { ApiError } from "@/lib/api";

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
