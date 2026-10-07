import Link from "next/link";
import { PackageSearch } from "lucide-react";
import { storefrontServer, publicFailure } from "@/lib/api/storefront-server";
import { ProductGrid } from "@/components/catalog/product-grid";
import { PublicError } from "@/components/catalog/public-error";
import { EmptyState } from "@/components/ui/empty-state";
import { buttonVariants } from "@/components/ui/button";

export default async function HomePage() {
  const [categories, newest, promotions] = await Promise.allSettled([
    storefrontServer.categories(), storefrontServer.search({ sort: "newest", size: 8, in_stock: false }),
    storefrontServer.search({ has_promotion: true, sort: "newest", size: 8, in_stock: false }),
  ]);
  return <main className="mx-auto w-full max-w-medium space-y-16 px-6 py-12 md:px-8 md:py-16">
    <header className="max-w-prose-title space-y-6"><p className="text-caption uppercase tracking-wide text-muted-foreground">TechNova · Công nghệ mỗi ngày</p><h1 className="font-serif text-headline-1 font-normal">Tìm điều phù hợp với bạn.</h1><p className="text-body-2 text-muted-foreground">Khám phá sản phẩm, so sánh biến thể và đọc trải nghiệm từ người đã mua.</p><Link href="/search?in_stock=false" className={buttonVariants({ variant: "clay" })}>Khám phá sản phẩm</Link></header>
    <section className="space-y-6"><h2 className="font-serif text-headline-3">Danh mục</h2>{categories.status === "rejected" ? <PublicError failure={publicFailure(categories.reason)} /> : categories.value.categories.length ? <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">{categories.value.categories.map((category) => <Link key={category.category_id} href={`/c/${encodeURIComponent(category.slug)}`} className="focus-ring rounded-xl border border-border bg-surface-2 p-6 hover:bg-surface-3"><h3 className="font-serif text-headline-6">{category.name}</h3>{category.product_count_updated_at && <p className="mt-2 text-caption text-muted-foreground">{category.product_count.toLocaleString("vi-VN")} sản phẩm</p>}</Link>)}</div> : <EmptyState icon={PackageSearch} title="Danh mục đang được cập nhật" description="Bạn có thể xem các sản phẩm bên dưới." action={<Link href="/search" className="focus-ring underline">Tìm sản phẩm</Link>} />}</section>
    {[{ title: "Sản phẩm mới", result: newest, href: "/search?sort=newest&in_stock=false" }, { title: "Đang khuyến mãi", result: promotions, href: "/search?has_promotion=true&in_stock=false" }].map(({ title, result, href }) => <section key={title} className="space-y-6"><div className="flex flex-wrap items-baseline justify-between gap-4"><h2 className="font-serif text-headline-3">{title}</h2><Link href={href} className="focus-ring flex min-h-11 items-center rounded-lg text-[14px] underline">Xem tất cả</Link></div>{result.status === "rejected" ? <PublicError failure={publicFailure(result.reason)} /> : result.value.items.length ? <ProductGrid items={result.value.items} /> : <EmptyState icon={PackageSearch} title="Chưa có sản phẩm trong mục này" description="Khám phá các mục khác trong cửa hàng." action={<Link href="/search?in_stock=false" className="focus-ring underline">Xem sản phẩm</Link>} />}</section>)}
  </main>;
}
