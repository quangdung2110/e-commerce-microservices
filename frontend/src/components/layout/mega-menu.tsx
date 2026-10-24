"use client";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Menu, ArrowRight } from "lucide-react";
import { Sheet, SheetContent, SheetHeader, SheetTitle, SheetDescription, SheetTrigger } from "@/components/ui/sheet";
import { searchApi, type CategoryNode } from "@/lib/api/search";
import { navigationCategories } from "@/lib/storefront-collection";

function CategoryLinks({ nodes, onNavigate }: { nodes: CategoryNode[]; onNavigate?: () => void }) {
  return <ul className="space-y-1">{nodes.map((node) => <li key={node.category_id}><Link onClick={onNavigate} href={`/c/${encodeURIComponent(node.slug)}`} className="focus-ring flex min-h-11 items-center rounded-lg px-3 text-[14px] hover:bg-accent">{node.name}</Link>{node.children.length > 0 && <div className="pl-4"><CategoryLinks nodes={node.children} onNavigate={onNavigate} /></div>}</li>)}</ul>;
}
export function MegaMenu() {
  const [open, setOpen] = useState(false);
  const query = useQuery({ queryKey: ["storefront-categories"], queryFn: ({ signal }) => searchApi.categories(signal), staleTime: 60_000 });
  const categories = navigationCategories(query.data?.categories ?? []);
  return <>
    <Sheet open={open} onOpenChange={setOpen}>
    <nav aria-label="Điều hướng mua sắm" className="flex items-center justify-between gap-3 py-1 lg:hidden">
      <SheetTrigger className="focus-ring inline-flex min-h-11 items-center gap-2 rounded-lg px-3 text-[14px] font-medium"><Menu className="size-4" aria-hidden />Danh mục</SheetTrigger>
      <Link href="/search?in_stock=false" className="focus-ring inline-flex min-h-11 items-center gap-2 rounded-lg px-3 text-[14px]">Tất cả sản phẩm<ArrowRight className="size-4" aria-hidden /></Link>
    </nav>
      <SheetContent closeLabel="Đóng danh mục" className="w-full sm:w-96">
        <SheetHeader className="mb-6"><SheetTitle>Danh mục sản phẩm</SheetTitle><SheetDescription>Chọn một danh mục để khám phá sản phẩm.</SheetDescription></SheetHeader>
        <nav aria-label="Danh mục trên điện thoại" className="min-h-0 flex-1 overflow-y-auto">
          <Link href="/search?in_stock=false" onClick={() => setOpen(false)} className="focus-ring mb-3 flex min-h-11 items-center rounded-lg bg-surface-2 px-3 text-[14px] font-medium">Tất cả sản phẩm</Link>
          {query.isPending && <p role="status" className="p-3 text-muted-foreground">Đang tải danh mục…</p>}
          {query.isError && <div role="alert" className="space-y-3 p-3"><p>Chưa tải được danh mục. Bạn vẫn có thể xem tất cả sản phẩm.</p><button type="button" onClick={() => void query.refetch()} className="focus-ring min-h-11 rounded-lg px-3 underline">Thử lại</button></div>}
          <CategoryLinks nodes={categories} onNavigate={() => setOpen(false)} />
        </nav>
      </SheetContent>
    </Sheet>
    <nav aria-label="Danh mục sản phẩm" className="relative hidden min-w-0 flex-wrap gap-1 py-2 lg:flex">
    <Link href="/search?in_stock=false" className="focus-ring flex min-h-11 items-center rounded-lg px-3 text-[14px] hover:bg-accent">Tất cả sản phẩm</Link>
    {categories.map((category) => category.children.length ? <details key={category.category_id} className="static sm:relative"><summary className="focus-ring flex min-h-11 cursor-pointer items-center rounded-lg px-3 text-[14px] hover:bg-accent">{category.name} ▾</summary><div className="absolute left-0 top-full z-50 max-h-96 w-full sm:w-64 overflow-y-auto rounded-xl border border-border bg-surface-overlay p-3 shadow-md"><Link href={`/c/${encodeURIComponent(category.slug)}`} className="focus-ring flex min-h-11 items-center rounded-lg px-3 text-[14px] font-medium hover:bg-accent">Tất cả {category.name}</Link><CategoryLinks nodes={category.children} /></div></details> : <Link key={category.category_id} href={`/c/${encodeURIComponent(category.slug)}`} className="focus-ring flex min-h-11 items-center rounded-lg px-3 text-[14px] hover:bg-accent">{category.name}</Link>)}
    </nav>
  </>;
}
