"use client";

// 銀行のロゴ表示部品。public/logo.png が無い・読めない場合は銀行アイコンで代用する。

import { useState } from "react";
import { IconBank } from "@/components/Icons";

/** Brand logo: frontend/public/logo.png. Falls back to the bank icon until the file exists. */
// 画像の読み込みに失敗したら（onError）アイコン表示に切り替える
export function Logo({ size = 34 }: { size?: number }) {
  const [failed, setFailed] = useState(false);
  if (failed) {
    return (
      <span className="brand-mark" aria-hidden="true" style={{ width: size, height: size }}>
        <IconBank size={Math.round(size * 0.53)} />
      </span>
    );
  }
  return (
    // eslint-disable-next-line @next/next/no-img-element -- small static logo, no optimisation needed
    <img src="/logo.png" alt="" width={size} height={size} className="brand-logo" onError={() => setFailed(true)} />
  );
}
