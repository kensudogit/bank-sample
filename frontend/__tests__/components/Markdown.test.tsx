// components/Markdown.tsx のテスト：見出し・段落・太字・コード・箇条書き・番号付き・表・引用・区切り線。HTML は文字として扱う。
import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { Markdown } from "@/components/Markdown";

const ANSWER = `振込の提案を作りました。**まだ実行されていません。**

**提案の内容**
| 項目 | 内容 |
|---|---|
| 振込元 | 1000001（**普通預金**） |
| 金額 | 5,000円 |

## 事前の確認
- 状態は \`ACTIVE\` です。
- 残高は足ります。

1. 内容を確認
2. 振込画面で実行

> 架空のデータです

---
最後の行
続きの行`;

describe("Markdown", () => {
  it("表・見出し・箇条書き・番号付き・引用・区切り線・太字・コードを要素にする", () => {
    const { container } = render(<Markdown text={ANSWER} />);
    expect(screen.getByText("まだ実行されていません。").tagName).toBe("STRONG");
    const table = screen.getByRole("table");
    expect(within(table).getAllByRole("columnheader").map((h) => h.textContent)).toEqual(["項目", "内容"]);
    expect(within(table).getAllByRole("row")).toHaveLength(3);
    expect(within(table).getByText("普通預金").tagName).toBe("STRONG");
    expect(screen.getByText("事前の確認").tagName).toBe("H4");
    expect(container.querySelector("ul")?.children).toHaveLength(2);
    expect(screen.getByText("ACTIVE").tagName).toBe("CODE");
    expect(container.querySelector("ol")?.children).toHaveLength(2);
    expect(container.querySelector("blockquote")).toHaveTextContent("架空のデータです");
    expect(container.querySelector("hr")).not.toBeNull();
    const last = screen.getByText(/最後の行/);
    expect(last.tagName).toBe("P");
    expect(last.querySelector("br")).not.toBeNull();
    // 記号そのものは残らない
    expect(container.textContent).not.toContain("**");
    expect(container.textContent).not.toContain("|---|");
  });

  it("HTML やスクリプトは解釈せず、文字として表示する。リンクも作らない", () => {
    const { container } = render(<Markdown text={'<img src=x onerror="alert(1)"> と [ここ](https://evil.example) <b>太</b>'} />);
    expect(container.querySelector("img")).toBeNull();
    expect(container.querySelector("b")).toBeNull();
    expect(container.querySelector("a")).toBeNull();
    expect(container.textContent).toContain('<img src=x onerror="alert(1)">');
  });

  it("区切り行のない | 行は表にせず段落にする", () => {
    render(<Markdown text={"| a | b |\nふつうの文"} />);
    expect(screen.queryByRole("table")).toBeNull();
    expect(screen.getByText(/\| a \| b \|/).tagName).toBe("P");
  });
});
