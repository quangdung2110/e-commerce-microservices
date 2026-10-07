"use client";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useTransition } from "react";
import type { ProductDetail } from "@/lib/api/product-detail";
import type { ReviewParams } from "@/lib/api/review";
import { skuLabel } from "@/lib/product-detail";
import { Button } from "@/components/ui/button";
import { NativeSelect } from "@/components/ui/native-select";
export function ReviewFilters({ product, params }: { product: ProductDetail; params: ReviewParams }) {
  const router = useRouter();
  const [pending, startTransition] = useTransition();
  const action = `/products/${encodeURIComponent(product.slug)}/reviews`;
  return <form className="flex flex-wrap items-end gap-4 rounded-xl border border-border bg-surface-overlay p-6" onSubmit={(event) => { event.preventDefault(); const query = new URLSearchParams(); for (const [key, value] of new FormData(event.currentTarget)) if (String(value)) query.append(key, String(value)); startTransition(() => router.push(`${action}?${query}`, { scroll: false })); }}>
    <div className="space-y-2"><label htmlFor="review-rating">Số sao</label><NativeSelect id="review-rating" name="rating" defaultValue={params.rating ?? ""}><option value="">Tất cả</option>{[5, 4, 3, 2, 1].map((star) => <option key={star} value={star}>{star} sao</option>)}</NativeSelect></div>
    <div className="min-w-0 flex-1 basis-40 space-y-2"><label htmlFor="review-sku">Biến thể</label><NativeSelect id="review-sku" name="sku_id" defaultValue={params.sku_id ?? ""}><option value="">Tất cả biến thể</option>{product.skus.map((sku) => <option key={sku.sku_id} value={sku.sku_id}>{skuLabel(product, sku)}</option>)}</NativeSelect></div>
    <label className="flex min-h-11 items-center gap-3"><input type="checkbox" name="has_media" value="true" defaultChecked={params.has_media} className="focus-ring size-4 accent-clay" />Có ảnh/video</label>
    <Button type="submit" variant="secondary" disabled={pending}>{pending ? "Đang lọc…" : "Áp dụng"}</Button>
    <Link href={action} className="focus-ring flex min-h-11 items-center rounded-lg text-[14px] underline">Xóa bộ lọc</Link>
  </form>;
}
