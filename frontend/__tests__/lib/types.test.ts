// lib/types.ts のテスト：型定義だけのモジュールなので、代表的な値が型として受け入れられること
// （コンパイル時チェック）と、実行時に何も export しないことを確認する。
import { describe, expect, expectTypeOf, it } from "vitest";
import * as types from "@/lib/types";
import type { Account, AccountType, LoginUser, Page, Role, TxType } from "@/lib/types";

describe("types", () => {
  it("実行時の値を export しない（型のみ）", () => {
    expect(Object.keys(types)).toEqual([]);
  });

  it("主要な型が想定どおりの形をしている", () => {
    expectTypeOf<Role>().toEqualTypeOf<"CUSTOMER" | "TELLER" | "SUPERVISOR">();
    expectTypeOf<"ORDINARY">().toMatchTypeOf<AccountType>();
    expectTypeOf<"REMIT_IN">().toMatchTypeOf<TxType>();
    expectTypeOf<Page<Account>["items"]>().toEqualTypeOf<Account[]>();
    expectTypeOf<LoginUser["customerId"]>().toEqualTypeOf<string | null>();
  });
});
