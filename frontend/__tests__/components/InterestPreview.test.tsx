// components/InterestPreview.tsx のテスト：利息見込みの取得・基準日の変更・計算結果の表示。
import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { InterestPreview, InterestTable } from "@/components/InterestPreview";
import type { InterestResult } from "@/lib/types";
import { ApiFailure, callsTo, mockApi } from "../../test/api";

const result: InterestResult = {
  accountNo: "1000001", accountType: "ORDINARY", periodFrom: "2026-09-01", periodTo: "2026-11-22",
  days: 83, balanceDays: 83, averageBalance: 1_000_000, grossInterest: 227, nationalTax: 34, localTax: 11, tax: 45,
  netInterest: 182, settled: false, note: null,
};

describe("InterestPreview", () => {
  it("基準日ごとに利息見込みを取得して表示する", async () => {
    mockApi({ "/interest-preview": result });
    render(<InterestPreview accountNo="1000001" />);
    expect(await screen.findByText("2026-09-01〜2026-11-22（83日）")).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("基準日"), { target: { value: "2027-03-01" } });
    await screen.findByText(/83日/);
    expect(callsTo("/interest-preview").map((c) => c.query.get("asOf"))).toContain("2027-03-01");
  });

  it("基準日を空にしても前の基準日のまま", async () => {
    mockApi({ "/interest-preview": result });
    render(<InterestPreview accountNo="1000001" />);
    const input = screen.getByLabelText("基準日") as HTMLInputElement;
    const before = input.value;
    fireEvent.change(input, { target: { value: "" } });
    expect(input.value).toBe(before);
  });

  it("エラーを表示する", async () => {
    mockApi({ "/interest-preview": new ApiFailure(404, "ACCOUNT_NOT_FOUND", "口座が見つかりません。") });
    render(<InterestPreview accountNo="9999999" />);
    expect(await screen.findByRole("alert")).toHaveTextContent("口座が見つかりません。");
  });
});

describe("InterestTable", () => {
  it("税引前・国税・地方税・税引後利息を表示する", () => {
    render(<InterestTable result={result} />);
    expect(screen.getByText("利息（税引前）").nextElementSibling).toHaveTextContent("227");
    expect(screen.getByText("受取利息（税引後）").nextElementSibling).toHaveTextContent("182");
  });

  it("外貨はセント単位から換算して表示する", () => {
    render(<InterestTable result={{ ...result, grossInterest: 1234 }} currency="USD" />);
    expect(screen.getByText("利息（税引前）").nextElementSibling).toHaveTextContent("12.34");
  });

  it("計算対象日が 0 日なら説明文を出す", () => {
    render(<InterestTable result={{ ...result, days: 0 }} />);
    expect(screen.getByText(/計算対象の日がありません/)).toBeInTheDocument();
  });
});
