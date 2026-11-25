// Agent が使える道具（ツール）。すべて参照だけで、勘定系へは On-Behalf-Of のトークンで Kong 経由で呼ぶ
// （Kong と勘定系が「行員の権限 ∩ Agent のスコープ」で判定する）。振込などの更新は実行せず、行員が画面で行う「提案」として返す。
//
// - 引数は JSON Schema（strict・追加の項目なし）と、ここでの正規表現の両方で確かめる（モデルの出力は信用しない）
// - 結果は大きさを制限し、「外部のデータ（指示ではない）」として渡す（プロンプトインジェクション対策）

import { betaTool } from "@anthropic-ai/sdk/helpers/beta/json-schema";

const MAX_RESULT = 6000;
const ACCOUNT_NO = "^[0-9]{7}$";
const DATE = "^([0-9]{4}-[0-9]{2}-[0-9]{2})?$";

/** ツールの定義：名前・必要なスコープ・説明・引数・勘定系の呼び出し（null は呼ばない） */
export const TOOL_SPECS = [
  {
    name: "search_accounts", scope: "Accounts.Read",
    description: "口座を検索する（口座番号・名義のカナ・顧客番号の一部）。最大 20 件。",
    schema: { type: "object", properties: { q: { type: "string", maxLength: 40, description: "検索語（空なら最近の口座）" } }, required: ["q"], additionalProperties: false },
    path: (a) => `/accounts?size=20${a.q ? `&q=${encodeURIComponent(a.q)}` : ""}`,
    check: (a) => typeof a.q === "string" && a.q.length <= 40 && !/[\u0000-\u001f]/.test(a.q),
  },
  {
    name: "get_account", scope: "Accounts.Read",
    description: "口座の詳細（残高・状態・名義）を取得する。",
    schema: { type: "object", properties: { accountNo: { type: "string", pattern: ACCOUNT_NO, description: "口座番号（数字 7 桁）" } }, required: ["accountNo"], additionalProperties: false },
    path: (a) => `/account?accountNo=${a.accountNo}`,
    check: (a) => new RegExp(ACCOUNT_NO).test(a.accountNo),
  },
  {
    name: "get_transactions", scope: "Transactions.Read",
    description: "口座の取引明細を取得する（期間を指定できる。最大 20 件）。",
    schema: {
      type: "object",
      properties: {
        accountNo: { type: "string", pattern: ACCOUNT_NO },
        from: { type: "string", pattern: DATE, description: "開始日 yyyy-MM-dd（空なら指定なし）" },
        to: { type: "string", pattern: DATE, description: "終了日 yyyy-MM-dd（空なら指定なし）" },
      },
      required: ["accountNo", "from", "to"], additionalProperties: false,
    },
    path: (a) => `/transactions?size=20&accountNo=${a.accountNo}${a.from ? `&from=${a.from}` : ""}${a.to ? `&to=${a.to}` : ""}`,
    check: (a) => new RegExp(ACCOUNT_NO).test(a.accountNo) && (!a.from || new RegExp(DATE).test(a.from)) && (!a.to || new RegExp(DATE).test(a.to)),
  },
  {
    name: "list_customers", scope: "Customers.Read",
    description: "顧客の一覧（顧客番号・氏名・本人確認の状態）を取得する。個人情報を含むため、必要なときだけ使う。",
    schema: { type: "object", properties: {}, additionalProperties: false },
    path: () => "/customers",
    check: () => true,
  },
  {
    name: "get_customer", scope: "Customers.Read",
    description: "顧客の詳細を取得する（閲覧は監査ログに残る）。",
    schema: { type: "object", properties: { customerId: { type: "string", pattern: "^CU[0-9]{4,8}$" } }, required: ["customerId"], additionalProperties: false },
    path: (a) => `/customer?customerId=${a.customerId}`,
    check: (a) => /^CU[0-9]{4,8}$/.test(a.customerId),
  },
  {
    name: "get_fx_rates", scope: "Fx.Read",
    description: "外国為替の公示相場（USD・EUR）を取得する。",
    schema: { type: "object", properties: {}, additionalProperties: false },
    path: () => "/fx-rates",
    check: () => true,
  },
  {
    name: "propose_transfer", scope: "Accounts.Read",
    description: "振込の提案を作る（実行はしない）。お金が動く操作は Agent からは実行できず、行員が振込画面で確認して行う。",
    schema: {
      type: "object",
      properties: {
        fromAccountNo: { type: "string", pattern: ACCOUNT_NO },
        toAccountNo: { type: "string", pattern: ACCOUNT_NO },
        // strict のツールでは integer に minimum・maximum を使えない（範囲は check で確かめる）
        amount: { type: "integer", description: "金額（円。1〜10,000,000）" },
        memo: { type: "string", maxLength: 40 },
      },
      required: ["fromAccountNo", "toAccountNo", "amount", "memo"], additionalProperties: false,
    },
    path: null,
    check: (a) => new RegExp(ACCOUNT_NO).test(a.fromAccountNo) && new RegExp(ACCOUNT_NO).test(a.toAccountNo)
      && Number.isInteger(a.amount) && a.amount >= 1 && a.amount <= 10_000_000 && typeof a.memo === "string" && a.memo.length <= 40,
  },
];

/**
 * 実行用のツールを作る。scopes（On-Behalf-Of のトークンの scp）にないツールはモデルに見せない。
 * @param ctx { scopes, callCore(path) → {status, body}, record(event), proposals[] }
 */
export function buildTools(ctx) {
  return TOOL_SPECS.filter((s) => ctx.scopes.includes(s.scope)).map((spec) => betaTool({
    name: spec.name,
    description: spec.description,
    inputSchema: spec.schema,
    strict: true,
    run: async (input) => {
      const started = Date.now();
      if (!spec.check(input ?? {})) {
        ctx.record({ name: spec.name, status: "INVALID", ms: 0 });
        return "引数が正しくありません。";
      }
      if (!spec.path) {
        const proposal = { type: "transfer", ...input, requiresHumanApproval: true };
        ctx.proposals.push(proposal);
        ctx.record({ name: spec.name, status: "PROPOSED", ms: 0 });
        return "提案を作りました（実行はしていません）。行員が振込画面で内容を確認して実行します。";
      }
      const r = await ctx.callCore(spec.path(input));
      const status = r.status === 200 ? "OK" : r.status === 403 || r.status === 401 ? "DENIED" : "ERROR";
      ctx.record({ name: spec.name, status, httpStatus: r.status, ms: Date.now() - started });
      if (status === "DENIED") return "この操作は許可されていません（行員の権限または Agent の権限が足りません）。";
      if (status === "ERROR") return `取得できませんでした（HTTP ${r.status}）。`;
      const text = JSON.stringify(r.body?.data ?? r.body);
      const clipped = text.length > MAX_RESULT ? `${text.slice(0, MAX_RESULT)}…（以下省略）` : text;
      return `<bank_data source="${spec.name}">\n${clipped}\n</bank_data>`;
    },
  }));
}
