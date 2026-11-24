// components/Feedback.tsx のテスト：エラー表示・成功表示・読み込み中スケルトン。
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ErrorBox, Loading, SuccessBox } from "@/components/Feedback";
import { ApiError } from "@/lib/api";

describe("ErrorBox", () => {
  it("error が空なら何も表示しない", () => {
    const { container } = render(<ErrorBox error={null} />);
    expect(container).toBeEmptyDOMElement();
  });

  it("ApiError のメッセージと項目別エラーを一覧表示する", () => {
    render(<ErrorBox error={new ApiError(400, "VALIDATION", "入力に誤りがあります。", { amount: ["金額は必須です。"], memo: ["長すぎます。"] })} />);
    const alert = screen.getByRole("alert");
    expect(alert).toHaveTextContent("入力に誤りがあります。");
    expect(screen.getAllByRole("listitem").map((li) => li.textContent)).toEqual(["金額は必須です。", "長すぎます。"]);
  });

  it("想定外の例外は内部情報を出さず汎用メッセージにする", () => {
    render(<ErrorBox error={new Error("NullPointerException at Foo.java:12")} />);
    expect(screen.getByRole("alert")).toHaveTextContent("予期しないエラーが発生しました。");
    expect(screen.queryByText(/NullPointer/)).not.toBeInTheDocument();
  });
});

describe("SuccessBox / Loading", () => {
  it("成功メッセージを status として表示する", () => {
    render(<SuccessBox>振込が完了しました。</SuccessBox>);
    expect(screen.getByRole("status")).toHaveTextContent("振込が完了しました。");
  });

  it("読み込み中をスクリーンリーダーに通知する", () => {
    render(<Loading />);
    expect(screen.getByRole("status", { name: "読み込み中" })).toBeInTheDocument();
  });
});
