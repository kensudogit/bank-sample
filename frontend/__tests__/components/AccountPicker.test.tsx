// components/AccountPicker.tsx のテスト：お客さまは本人口座のプルダウン、行員は口座番号の入力。
import { screen, waitFor } from "@testing-library/react";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";
import { AccountPicker } from "@/components/AccountPicker";
import { mockApi } from "../../test/api";
import { account, accountPage, customerUser, renderWithAuth, tellerUser } from "../../test/render";

function Harness({ onChange }: { onChange?: (v: string) => void }) {
  const [v, setV] = useState("");
  return (
    <>
      <AccountPicker label="出金口座" value={v} onChange={(x) => { setV(x); onChange?.(x); }} />
      <output data-testid="value">{v}</output>
    </>
  );
}

describe("AccountPicker", () => {
  it("お客さま：利用可能な円口座（定期を除く）だけを選択肢にし、先頭を自動選択する", async () => {
    mockApi({
      "/accounts": accountPage([
        account({ accountNo: "1000001" }),
        account({ accountNo: "1000002", accountType: "TIME" }),
        account({ accountNo: "1000003", status: "FROZEN" }),
        account({ accountNo: "1000004", currency: "USD", accountType: "FOREIGN" }),
        account({ accountNo: "1000005", accountType: "SAVINGS" }),
      ]),
    });
    await renderWithAuth(<Harness />, customerUser);
    const select = await screen.findByRole("combobox", { name: /出金口座/ });
    await waitFor(() => expect(screen.getByTestId("value")).toHaveTextContent("1000001"));
    const options = Array.from((select as HTMLSelectElement).options).map((o) => o.value);
    expect(options).toEqual(["1000001", "1000005"]);
  });

  it("お客さま：選べる口座が無ければその旨を表示する", async () => {
    mockApi({ "/accounts": accountPage([account({ status: "DORMANT" })]) });
    await renderWithAuth(<Harness />, customerUser);
    expect(await screen.findByRole("option", { name: "選べる口座がありません" })).toBeInTheDocument();
  });

  it("行員：口座番号を入力し、全角数字は半角に正規化する", async () => {
    mockApi({ "/accounts": accountPage([]) });
    const onChange = vi.fn();
    const { user } = await renderWithAuth(<Harness onChange={onChange} />, tellerUser);
    const input = screen.getByRole("textbox", { name: /出金口座/ });
    expect(input).toHaveAttribute("maxLength", "7");
    await user.type(input, "１００");
    expect(screen.getByTestId("value")).toHaveTextContent("100");
    expect(onChange).toHaveBeenLastCalledWith("100");
  });
});
