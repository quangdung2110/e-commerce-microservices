"use client";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useId, useState, useTransition } from "react";
import { SlidersHorizontal, X } from "lucide-react";
import type { CategoryNode, FacetSet, SearchParams } from "@/lib/api/search";
import { flattenCategories, SEARCH_SORTS } from "@/lib/storefront-search";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { NativeSelect } from "@/components/ui/native-select";
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle, SheetTrigger } from "@/components/ui/sheet";
import { FacetGroup } from "./facet-group";

type FilterProps = { params: SearchParams; categories: CategoryNode[]; facets: FacetSet | null; action: string; fixedCategory?: boolean; priceInputs?: { min?: string; max?: string } };

export function CatalogFilters(props: FilterProps) {
  const [open, setOpen] = useState(false);
  const { params, fixedCategory, priceInputs } = props;
  const count = Number(!fixedCategory && Boolean(params.category))
    + Number(Boolean(priceInputs?.min || priceInputs?.max || params.price_min !== undefined || params.price_max !== undefined))
    + Number(params.in_stock === true) + Number(Boolean(params.has_promotion))
    + (params.filter?.length ?? 0);
  const sort = SEARCH_SORTS.find(([value]) => value === params.sort)?.[1] ?? "Phù hợp nhất";

  return <>
    <div className="space-y-3 lg:hidden">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <Sheet open={open} onOpenChange={setOpen}>
          <SheetTrigger className="focus-ring inline-flex min-h-11 items-center gap-2 rounded-lg border border-border-strong bg-surface-overlay px-4 text-[14px] font-medium">
            <SlidersHorizontal className="size-4" aria-hidden />Lọc và sắp xếp{count > 0 && <span className="rounded-md bg-surface-3 px-2 py-0.5 text-caption" aria-label={`${count} bộ lọc đang áp dụng`}>{count}</span>}
          </SheetTrigger>
          <SheetContent side="right" closeLabel="Đóng bộ lọc" className="w-full sm:w-96">
            <SheetHeader className="mb-6">
              <SheetTitle>Lọc và sắp xếp</SheetTitle>
              <SheetDescription>Chọn điều bạn cần rồi nhấn Áp dụng để xem sản phẩm.</SheetDescription>
            </SheetHeader>
            <CatalogFilterForm {...props} onApplied={() => setOpen(false)} inSheet />
          </SheetContent>
        </Sheet>
        <p className="text-caption text-muted-foreground">{sort}</p>
      </div>
      {count > 0 && <div className="flex flex-wrap items-center gap-2 text-caption text-muted-foreground">
        <span>{count} bộ lọc đang áp dụng</span>
        <Link href={clearFiltersHref(props)} className="focus-ring inline-flex min-h-11 items-center gap-1 rounded-lg px-2 text-foreground underline underline-offset-4"><X className="size-3" aria-hidden />Xóa bộ lọc</Link>
      </div>}
    </div>
    <div className="hidden lg:block"><CatalogFilterForm {...props} /></div>
  </>;
}

function clearFiltersHref({ action, params }: FilterProps) {
  const query = new URLSearchParams({ in_stock: "false" });
  if (params.q) query.set("q", params.q);
  return `${action}?${query}`;
}

function CatalogFilterForm({ params, categories, facets, action, fixedCategory = false, priceInputs, onApplied, inSheet = false }: FilterProps & { onApplied?: () => void; inSheet?: boolean }) {
  const router = useRouter();
  const id = useId();
  const [pending, startTransition] = useTransition();
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState(() => new Set(params.filter ?? []));
  const relevantFacets = facets?.filters.filter(facet => facet.options.length > 1 || facet.options.some(option => selected.has(option.value))) ?? [];
  const labels = new Map(facets?.filters.flatMap(facet => facet.options.map(option => [option.value, `${facet.label}: ${option.label}`] as const)));
  return <form aria-label="Lọc sản phẩm" className={inSheet ? "flex min-h-0 flex-1 flex-col" : "flex max-h-[calc(100svh-10rem)] flex-col rounded-xl border border-border bg-surface-overlay p-6 lg:sticky lg:top-36"} onSubmit={(event) => {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const min = String(data.get("price_min") ?? ""), max = String(data.get("price_max") ?? "");
    if (min && max && Number(min) > Number(max)) { setError("Giá tối đa phải lớn hơn hoặc bằng giá tối thiểu."); return; }
    setError(null);
    const query = new URLSearchParams();
    for (const [key, value] of data) if (String(value)) query.append(key, String(value));
    startTransition(() => { router.push(`${action}?${query.toString()}`, { scroll: false }); onApplied?.(); });
  }}>
    <div className="min-h-0 flex-1 space-y-5 overflow-y-auto pr-1">
    {!inSheet && <h2 className="font-serif text-headline-6">Lọc và sắp xếp</h2>}
    <input type="hidden" name="q" value={params.q ?? ""} />
    {[...selected].map((token, index) => <input type="hidden" key={token} name="filter" value={token} data-selected-filter={index} />)}
    {selected.size > 0 && <div className="space-y-2" aria-label="Thuộc tính đã chọn"><p className="text-caption text-muted-foreground">Thuộc tính đã chọn</p><div className="flex flex-wrap gap-2">{[...selected].map((token, index) => <button type="button" key={token} onClick={() => setSelected(current => { const next = new Set(current); next.delete(token); return next; })} className="focus-ring inline-flex min-h-11 items-center gap-2 rounded-lg bg-surface-2 px-3 text-left text-caption" aria-label={`Bỏ ${labels.get(token) ?? `bộ lọc từ liên kết ${index + 1}`}`}>{labels.get(token) ?? `Bộ lọc từ liên kết ${index + 1}`}<X className="size-3 shrink-0" aria-hidden /></button>)}</div></div>}
    {!fixedCategory && <div className="space-y-2"><label htmlFor={`${id}-category`}>Danh mục</label><NativeSelect id={`${id}-category`} name="category" defaultValue={params.category ?? ""}><option value="">Tất cả danh mục</option>{flattenCategories(categories).map((node) => <option value={node.slug} key={node.category_id}>{"— ".repeat(node.depth)}{node.name}</option>)}</NativeSelect></div>}
    <div className="space-y-2"><label htmlFor={`${id}-sort`}>Sắp xếp</label><NativeSelect id={`${id}-sort`} name="sort" defaultValue={params.sort}>{SEARCH_SORTS.map(([value, name]) => <option key={value} value={value}>{name}</option>)}</NativeSelect></div>
    <fieldset className="space-y-3"><legend className="mb-2">Khoảng giá (₫)</legend><div className="grid grid-cols-2 gap-3"><label htmlFor={`${id}-min`} className="sr-only">Giá tối thiểu</label><Input id={`${id}-min`} name="price_min" type="number" min={0} max={Number.MAX_SAFE_INTEGER} step={1} placeholder="Từ" defaultValue={priceInputs?.min ?? params.price_min} /><label htmlFor={`${id}-max`} className="sr-only">Giá tối đa</label><Input id={`${id}-max`} name="price_max" type="number" min={0} max={Number.MAX_SAFE_INTEGER} step={1} placeholder="Đến" defaultValue={priceInputs?.max ?? params.price_max} /></div></fieldset>
    {!params.category && <p className="text-caption text-muted-foreground">Chọn danh mục để lọc thêm theo thông số phù hợp.</p>}
    {relevantFacets.map((facet, index) => <FacetGroup key={facet.code} facet={facet} selected={selected} initiallyOpen={index === 0} disabled={pending} onChange={(token, checked) => setSelected(current => { const next = new Set(current); if (checked) next.add(token); else next.delete(token); return next; })} />)}
    <details className="space-y-4" open={params.has_promotion || undefined}><summary className="focus-ring min-h-11 cursor-pointer rounded-lg py-3 text-[14px] font-medium">Tình trạng và ưu đãi</summary>
    <div className="space-y-2"><label htmlFor={`${id}-stock`}>Tình trạng hàng</label><NativeSelect id={`${id}-stock`} name="in_stock" defaultValue={String(params.in_stock ?? true)}><option value="true">Chỉ còn hàng</option><option value="false">Tất cả, kể cả tạm hết hàng</option></NativeSelect></div>
    <label className="flex min-h-11 items-center gap-3"><input type="checkbox" name="has_promotion" value="true" defaultChecked={params.has_promotion} className="focus-ring size-4 accent-clay" />Đang khuyến mãi</label>
    </details>
    {error && <p role="alert" className="text-caption text-danger">{error}</p>}
    </div>
    <div className="mt-4 flex shrink-0 flex-wrap items-center gap-4 border-t border-border bg-surface-overlay pt-4"><Button type="submit" disabled={pending}>{pending ? "Đang lọc…" : "Áp dụng"}</Button><Link href={clearFiltersHref({ params, action, categories, facets })} onClick={onApplied} className="focus-ring flex min-h-11 items-center rounded-lg text-[14px] underline">Xóa bộ lọc</Link></div>
  </form>;
}
