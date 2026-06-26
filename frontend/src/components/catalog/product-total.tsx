"use client";

import { useSearchStore } from "@/store/useSearchStore";

export function ProductTotal() {
  const total = useSearchStore((state) => state.total);

  return (
    <p className="text-white/50 mt-2 font-mono text-sm tracking-wide">
      Khám phá <span className="font-black text-forest-bright tabular-nums">{total.toLocaleString()}</span> sản phẩm công nghệ đỉnh cao.
    </p>
  );
}
