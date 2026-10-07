"use client";
import { useState } from "react";
import type { DetailMedia } from "@/lib/api/product-detail";
import { StorefrontImage } from "@/components/catalog/storefront-image";

export function Gallery({ media, fallback, name }: { media: DetailMedia[]; fallback: string; name: string }) {
  const [index, setIndex] = useState(0);
  const current = media[index];
  return <div className="space-y-4">
    <div className="aspect-square overflow-hidden rounded-xl border border-border bg-surface-2">
      {current?.media_type === "VIDEO" ? <video src={current.url} controls preload="metadata" aria-label={current.alt_text ?? name} className="size-full object-contain" /> : <StorefrontImage src={current?.url ?? fallback} alt={current?.alt_text ?? name} className="size-full object-contain" />}
    </div>
    {media.length > 1 && <div className="flex flex-wrap gap-3">{media.map((item, i) => <button key={item.media_id} type="button" aria-label={`Xem ${item.media_type === "VIDEO" ? "video" : "ảnh"} ${i + 1}`} aria-pressed={i === index} onClick={() => setIndex(i)} className={`focus-ring size-16 overflow-hidden rounded-lg border ${i === index ? "border-border-strong" : "border-border"}`}>{item.media_type === "IMAGE" ? <StorefrontImage src={item.url} alt="" className="size-full object-contain" /> : <span className="text-caption">Video</span>}</button>)}</div>}
  </div>;
}
