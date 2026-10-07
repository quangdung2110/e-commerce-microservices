"use client";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { searchApi, type CategoryNode } from "@/lib/api/search";

function CategoryLinks({ nodes }: { nodes: CategoryNode[] }) {
  return <ul className="space-y-1">{nodes.map((node) => <li key={node.category_id}><Link href={`/c/${encodeURIComponent(node.slug)}`} className="focus-ring flex min-h-11 items-center rounded-lg px-3 text-[14px] hover:bg-accent">{node.name}</Link>{node.children.length > 0 && <div className="pl-4"><CategoryLinks nodes={node.children} /></div>}</li>)}</ul>;
}
export function MegaMenu() {
  const query = useQuery({ queryKey: ["storefront-categories"], queryFn: ({ signal }) => searchApi.categories(signal), staleTime: 60_000 });
  return <nav aria-label="Danh mục sản phẩm" className="relative flex min-w-0 flex-wrap gap-1 py-2">
    <Link href="/search?in_stock=false" className="focus-ring flex min-h-11 items-center rounded-lg px-3 text-[14px] hover:bg-accent">Tất cả sản phẩm</Link>
    {query.data?.categories.map((category) => category.children.length ? <details key={category.category_id} className="static sm:relative"><summary className="focus-ring flex min-h-11 cursor-pointer items-center rounded-lg px-3 text-[14px] hover:bg-accent">{category.name} ▾</summary><div className="absolute left-0 top-full z-50 max-h-96 w-full sm:w-64 overflow-y-auto rounded-xl border border-border bg-surface-overlay p-3 shadow-md"><Link href={`/c/${encodeURIComponent(category.slug)}`} className="focus-ring flex min-h-11 items-center rounded-lg px-3 text-[14px] font-medium hover:bg-accent">Tất cả {category.name}</Link><CategoryLinks nodes={category.children} /></div></details> : <Link key={category.category_id} href={`/c/${encodeURIComponent(category.slug)}`} className="focus-ring flex min-h-11 items-center rounded-lg px-3 text-[14px] hover:bg-accent">{category.name}</Link>)}
  </nav>;
}
