"use client";

import { useState } from "react";
import { IconBank } from "@/components/Icons";

/** Brand logo: frontend/public/logo.png. Falls back to the bank icon until the file exists. */
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
