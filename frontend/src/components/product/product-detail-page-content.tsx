"use client";
import Link from "next/link";
import { useState } from "react";
import type { ProductDetail, AttributeScalar } from "@/lib/api/product-detail";
import { scalarLabel, resolveSku, skuGallery, pdpQuantity } from "@/lib/product-detail";
import { formatISODate, formatVND } from "@/lib/format";
import { Gallery } from "./gallery";
import { AddToCartButton } from "./add-to-cart-button";
import { Input } from "@/components/ui/input";
import { Alert } from "@/components/ui/alert";

const STOCK_LABELS = { IN_STOCK: "Còn hàng", OUT_OF_STOCK: "Tạm hết hàng", UNKNOWN: "Chưa xác định tình trạng hàng" } as const;
export function ProductDetailPageContent({ product, preferredSku, preferredQuantity }: { product: ProductDetail; preferredSku?: string; preferredQuantity?: number }) {
  const first = product.skus.find((sku) => sku.sku_id === preferredSku) ?? product.skus[0];
  const [selected, setSelected] = useState<Record<string, AttributeScalar>>(first.variant_attributes);
  const [quantity, setQuantity] = useState(() => pdpQuantity(preferredQuantity));
  const [locked, setLocked] = useState(false);
  const sku = resolveSku(product, selected);
  const media = skuGallery(product, sku);
  return <main className="mx-auto w-full max-w-medium space-y-12 px-6 py-10 md:px-8 md:py-14">
    <nav aria-label="Đường dẫn" className="flex flex-wrap items-center gap-2 text-caption text-muted-foreground"><Link href="/" className="focus-ring rounded-sm hover:underline">Trang chủ</Link>{product.category.path.map((node) => <span key={node.category_id} className="contents"><span aria-hidden>/</span><Link href={`/c/${encodeURIComponent(node.slug)}`} className="focus-ring rounded-sm hover:underline">{node.name}</Link></span>)}<span aria-hidden>/</span><span>{product.name}</span></nav>
    <div className="grid items-start gap-8 md:grid-cols-2 lg:gap-12">
      <Gallery key={sku?.sku_id ?? "product"} media={media} fallback={sku?.thumbnail_url ?? first.thumbnail_url} name={product.name} />
      <div className="space-y-6">
        <h1 className="font-serif text-headline-2 font-normal">{product.name}</h1>
        {sku ? <div className="space-y-2" aria-live="polite"><p className="font-serif text-headline-3 tabular-nums">{formatVND(sku.selling_price_vnd)}</p>{sku.base_price_vnd > sku.selling_price_vnd && <p className="text-muted-foreground line-through">{formatVND(sku.base_price_vnd)}</p>}<p className={`text-[14px] ${sku.availability_status === "IN_STOCK" ? "text-success" : "text-muted-foreground"}`}>{STOCK_LABELS[sku.availability_status]}</p></div> : <Alert variant="warning">Tổ hợp này chưa có sản phẩm. Hãy chọn phân loại khác.</Alert>}
        {product.variant_axes.map((axis) => <fieldset key={axis.code} disabled={locked} className="space-y-3"><legend className="mb-3 font-medium">{axis.name}</legend><div className="flex flex-wrap gap-2">{axis.options.map((option) => <button key={JSON.stringify(option.value)} type="button" aria-pressed={selected[axis.code] === option.value} onClick={() => { setSelected((previous) => ({ ...previous, [axis.code]: option.value })); setQuantity(1); }} className={`focus-ring min-h-11 rounded-lg border px-4 py-2 text-[14px] disabled:opacity-50 ${selected[axis.code] === option.value ? "border-border-strong bg-surface-3" : "border-border bg-surface-overlay hover:bg-surface-2"}`}>{option.label ?? scalarLabel(option.value)}</button>)}</div></fieldset>)}
        {sku?.availability_status === "UNKNOWN" && <Alert variant="info">Tình trạng hàng sẽ được kiểm tra lại khi đặt hàng. Bạn vẫn có thể thêm sản phẩm vào giỏ.</Alert>}
        <div className="space-y-2"><label htmlFor="pdp-quantity">Số lượng</label><Input id="pdp-quantity" type="number" inputMode="numeric" min={1} max={100} step={1} value={quantity} disabled={locked} className="max-w-32" onChange={(event) => { const value = Number(event.target.value); if (Number.isInteger(value) && value >= 1 && value <= 100) setQuantity(value); }} /></div>
        <AddToCartButton sku={sku} quantity={quantity} slug={product.slug} onLockChange={setLocked} />
        <p className="text-caption text-muted-foreground">Giá và tồn kho được xác nhận lại khi đặt hàng.</p>
      </div>
    </div>
    <section className="space-y-6"><h2 className="font-serif text-headline-3">Thông tin sản phẩm</h2><p className="max-w-prose-narrow whitespace-pre-line text-body-2 text-foreground-secondary">{product.description || "Thông tin mô tả đang được cập nhật."}</p></section>
    <section className="space-y-6"><h2 className="font-serif text-headline-3">Thông số</h2>{product.attributes.length ? <dl className="divide-y divide-border rounded-xl border border-border bg-surface-overlay px-6">{product.attributes.map((attribute) => <div key={attribute.code} className="grid gap-2 py-4 sm:grid-cols-2"><dt className="text-muted-foreground">{attribute.name}</dt><dd className="break-words">{attribute.value_label ?? scalarLabel(attribute.value)}</dd></div>)}</dl> : <p className="text-muted-foreground">Thông số đang được cập nhật.</p>}</section>
    <section className="space-y-6"><div className="flex flex-wrap items-baseline justify-between gap-4"><h2 className="font-serif text-headline-3">Đánh giá từ người mua</h2><Link href={`/products/${encodeURIComponent(product.slug)}/reviews`} className="focus-ring flex min-h-11 items-center rounded-lg underline">Xem tất cả đánh giá</Link></div>
      {product.review_section.status === "UNAVAILABLE" ? <Alert variant="info">Đánh giá đang tạm gián đoạn. Bạn vẫn có thể xem thông tin và chọn sản phẩm.</Alert> : <>
        <p className="text-muted-foreground">{product.review_section.summary?.total_reviews ? `${product.review_section.summary.average_rating.toFixed(1)}/5 · ${product.review_section.summary.total_reviews} đánh giá` : "Chưa có đánh giá cho sản phẩm này."}</p>
        <div className="divide-y divide-border">{product.review_section.preview.map((review) => <article key={review.review_id} className="space-y-3 py-6"><div className="flex flex-wrap justify-between gap-2"><p className="font-medium">{review.author_name} · {review.rating}/5 sao</p><time dateTime={review.created_at} className="text-caption text-muted-foreground">{formatISODate(review.created_at)}</time></div>{review.variant_name && <p className="text-caption text-muted-foreground">{review.variant_name}</p>}{review.content && <p className="whitespace-pre-line break-words">{review.content}</p>}{review.has_media && <p className="text-caption text-muted-foreground">Có ảnh/video trong trang đánh giá đầy đủ</p>}</article>)}</div>
      </>}
    </section>
  </main>;
}
