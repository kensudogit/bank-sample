// lib/query.ts のテスト：stale-while-revalidate キャッシュ（useQuery）、先読み、無効化、全消去。
import { act, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { clearCache, invalidate, prefetch, useQuery, type QueryOptions } from "@/lib/query";

/** useQuery の状態をそのまま表示するテスト用部品 */
function Probe({ k, fetcher, options }: { k: string; fetcher: () => Promise<string>; options?: QueryOptions }) {
  const q = useQuery(k, fetcher, options);
  return (
    <div>
      <span data-testid="data">{q.data ?? "-"}</span>
      <span data-testid="loading">{String(q.loading)}</span>
      <span data-testid="placeholder">{String(q.placeholder)}</span>
      <span data-testid="error">{q.error ? String((q.error as Error).message) : "-"}</span>
      <button onClick={q.reload}>reload</button>
    </div>
  );
}

const text = (id: string) => screen.getByTestId(id).textContent;

describe("useQuery", () => {
  it("取得したデータを表示し、同じキーの同時リクエストは 1 回にまとめる", async () => {
    const fetcher = vi.fn(async () => "A");
    render(<><Probe k="q1" fetcher={fetcher} /><Probe k="q1" fetcher={fetcher} /></>);
    await waitFor(() => expect(screen.getAllByTestId("data").map((e) => e.textContent)).toEqual(["A", "A"]));
    expect(fetcher).toHaveBeenCalledTimes(1);
  });

  it("新しいデータは再マウントしても再取得しない（FRESH 期間内）", async () => {
    const fetcher = vi.fn(async () => "A");
    const { unmount } = render(<Probe k="q2" fetcher={fetcher} />);
    await waitFor(() => expect(text("data")).toBe("A"));
    unmount();
    render(<Probe k="q2" fetcher={fetcher} />);
    expect(text("data")).toBe("A");
    expect(fetcher).toHaveBeenCalledTimes(1);
  });

  it("エラーを返し、reload で再取得できる", async () => {
    const fetcher = vi.fn().mockRejectedValueOnce(new Error("boom")).mockResolvedValueOnce("OK");
    render(<Probe k="q3" fetcher={fetcher} />);
    await waitFor(() => expect(text("error")).toBe("boom"));
    act(() => screen.getByText("reload").click());
    await waitFor(() => expect(text("data")).toBe("OK"));
    expect(text("error")).toBe("-");
  });

  it("keepPreviousWithin：同じスコープ内のキー変更中は前の結果を表示し続ける", async () => {
    let resolveB: (v: string) => void = () => undefined;
    const { rerender } = render(<Probe k="list:1" fetcher={async () => "page1"} options={{ keepPreviousWithin: "list" }} />);
    await waitFor(() => expect(text("data")).toBe("page1"));
    rerender(<Probe k="list:2" fetcher={() => new Promise<string>((r) => (resolveB = r))} options={{ keepPreviousWithin: "list" }} />);
    expect(text("data")).toBe("page1");
    expect(text("placeholder")).toBe("true");
    expect(text("loading")).toBe("true");
    await act(async () => resolveB("page2"));
    await waitFor(() => expect(text("data")).toBe("page2"));
    expect(text("placeholder")).toBe("false");
  });

  it("スコープが違えば前の結果を引き継がない", async () => {
    const { rerender } = render(<Probe k="a:1" fetcher={async () => "A"} options={{ keepPreviousWithin: "a" }} />);
    await waitFor(() => expect(text("data")).toBe("A"));
    rerender(<Probe k="b:1" fetcher={() => new Promise<string>(() => undefined)} options={{ keepPreviousWithin: "b" }} />);
    expect(text("data")).toBe("-");
  });
});

describe("invalidate / prefetch / clearCache", () => {
  it("invalidate は表示中のキーを即再取得する（* でプレフィックス一致）", async () => {
    let n = 0;
    const fetcher = vi.fn(async () => `v${++n}`);
    render(<Probe k="loans:ACTIVE" fetcher={fetcher} />);
    await waitFor(() => expect(text("data")).toBe("v1"));
    act(() => invalidate("loans*"));
    await waitFor(() => expect(text("data")).toBe("v2"));
    act(() => invalidate("no-such-key"));
    expect(fetcher).toHaveBeenCalledTimes(2);
  });

  it("invalidate 前に始まった古いリクエストの結果で上書きしない", async () => {
    const resolvers: ((v: string) => void)[] = [];
    const fetcher = () => new Promise<string>((r) => resolvers.push(r));
    render(<Probe k="race" fetcher={fetcher} />);
    await waitFor(() => expect(resolvers.length).toBe(1));
    act(() => invalidate("race"));
    await waitFor(() => expect(resolvers.length).toBe(2));
    await act(async () => resolvers[1]("new"));
    await act(async () => resolvers[0]("old"));
    expect(text("data")).toBe("new");
  });

  it("prefetch で温めたキャッシュはマウント時に即表示される", async () => {
    const fetcher = vi.fn(async () => "warm");
    prefetch("pf", fetcher);
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(1));
    await act(async () => undefined);
    render(<Probe k="pf" fetcher={fetcher} />);
    expect(text("data")).toBe("warm");
    expect(fetcher).toHaveBeenCalledTimes(1);
  });

  it("clearCache は表示中のデータも消す", async () => {
    let n = 0;
    render(<Probe k="cc" fetcher={async () => `u${++n}`} />);
    await waitFor(() => expect(text("data")).toBe("u1"));
    act(() => clearCache());
    expect(text("data")).toBe("-");
  });

  it("タブに戻ったとき、30 秒より古い表示中データだけ再取得する", async () => {
    const fetcher = vi.fn(async () => "x");
    render(<Probe k="vis" fetcher={fetcher} />);
    await waitFor(() => expect(text("data")).toBe("x"));
    const now = Date.now();
    vi.spyOn(Date, "now").mockReturnValue(now + 10_000);
    document.dispatchEvent(new Event("visibilitychange"));
    expect(fetcher).toHaveBeenCalledTimes(1);
    vi.spyOn(Date, "now").mockReturnValue(now + 31_000);
    document.dispatchEvent(new Event("visibilitychange"));
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2));
  });
});
