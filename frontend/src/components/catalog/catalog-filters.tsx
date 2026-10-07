"use client";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";
import type { CategoryNode, FacetSet, SearchParams } from "@/lib/api/search";
import { flattenCategories, SEARCH_SORTS } from "@/lib/storefront-search";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { NativeSelect } from "@/components/ui/native-select";

export function CatalogFilters({ params, categories, facets, action, fixedCategory = false }: { params: SearchParams; categories: CategoryNode[]; facets: FacetSet | null; action: string; fixedCategory?: boolean }) {
  const router = useRouter();
  const [pending, startTransition] = useTransition();
  const [error, setError] = useState<string | null>(null);
  return <form className="space-y-6 rounded-xl border border-border bg-surface-overlay p-6" onSubmit={(event) => {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const min = String(data.get("price_min") ?? ""), max = String(data.get("price_max") ?? "");
    if (min && max && Number(min) > Number(max)) { setError("Giá tối đa phải lớn hơn hoặc bằng giá tối thiểu."); return; }
    setError(null);
    const query = new URLSearchParams();
    for (const [key, value] of data) if (String(value)) query.append(key, String(value));
    startTransition(() => router.push(`${action}?${query.toString()}`, { scroll: false }));
  }}>
    <h2 className="font-serif text-headline-6">Lọc và sắp xếp</h2>
    <div className="space-y-2"><label htmlFor="catalog-q">Từ khóa</label><Input id="catalog-q" name="q" maxLength={256} defaultValue={params.q} placeholder="Tên sản phẩm…" /></div>
    {!fixedCategory && <div className="space-y-2"><label htmlFor="catalog-category">Danh mục</label><NativeSelect id="catalog-category" name="category" defaultValue={params.category ?? ""}><option value="">Tất cả danh mục</option>{flattenCategories(categories).map((node) => <option value={node.slug} key={node.category_id}>{"— ".repeat(node.depth)}{node.name}</option>)}</NativeSelect></div>}
    <div className="space-y-2"><label htmlFor="catalog-sort">Sắp xếp</label><NativeSelect id="catalog-sort" name="sort" defaultValue={params.sort}>{SEARCH_SORTS.map(([value, name]) => <option key={value} value={value}>{name}</option>)}</NativeSelect></div>
    <fieldset className="space-y-3"><legend className="mb-2">Khoảng giá (₫)</legend><label htmlFor="price-min" className="sr-only">Giá tối thiểu</label><Input id="price-min" name="price_min" type="number" min={0} max={Number.MAX_SAFE_INTEGER} step={1} placeholder="Từ" defaultValue={params.price_min} /><label htmlFor="price-max" className="sr-only">Giá tối đa</label><Input id="price-max" name="price_max" type="number" min={0} max={Number.MAX_SAFE_INTEGER} step={1} placeholder="Đến" defaultValue={params.price_max} /></fieldset>
    <div className="space-y-2"><label htmlFor="catalog-stock">Tình trạng hàng</label><NativeSelect id="catalog-stock" name="in_stock" defaultValue={String(params.in_stock)}><option value="true">Chỉ còn hàng</option><option value="false">Tất cả, kể cả tạm hết hàng</option></NativeSelect></div>
    <label className="flex min-h-11 items-center gap-3"><input type="checkbox" name="has_promotion" value="true" defaultChecked={params.has_promotion} className="focus-ring size-4 accent-clay" />Đang khuyến mãi</label>
    {facets?.filters.map((facet) => <fieldset key={facet.code}><legend className="mb-2 font-medium">{facet.label}</legend>{facet.options.map((option) => <label key={option.value} className="flex min-h-11 items-center gap-3"><input type="checkbox" name="filter" value={option.value} defaultChecked={params.filter?.includes(option.value)} className="focus-ring size-4 accent-clay" /><span>{option.label} <span className="text-caption text-muted-foreground">(≈{option.count.toLocaleString("vi-VN")} sản phẩm)</span></span></label>)}</fieldset>)}
    {params.filter?.filter((token) => !facets?.filters.some((facet) => facet.options.some((option) => option.value === token))).map((token) => <input type="hidden" key={token} name="filter" value={token} />)}
    {error && <p role="alert" className="text-caption text-danger">{error}</p>}
    <div className="flex flex-wrap items-center gap-4"><Button type="submit" variant="secondary" disabled={pending}>{pending ? "Đang lọc…" : "Áp dụng"}</Button><Link href={action} className="focus-ring flex min-h-11 items-center rounded-lg text-[14px] underline">Xóa bộ lọc</Link></div>
  </form>;
}
