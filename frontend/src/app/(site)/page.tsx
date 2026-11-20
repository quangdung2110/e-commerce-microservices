import Link from "next/link";
import { Suspense } from "react";
import { ArrowRight, PackageSearch } from "lucide-react";
import { storefrontServer, publicFailure } from "@/lib/api/storefront-server";
import { ProductGrid, ProductGridSkeleton } from "@/components/catalog/product-grid";
import { PublicError } from "@/components/catalog/public-error";
import { EmptyState } from "@/components/ui/empty-state";
import { HomeHero } from "@/components/catalog/home-hero";
import { featuredProducts, navigationCategories } from "@/lib/storefront-collection";

export default function HomePage() {
  return <main className="mx-auto w-full max-w-medium space-y-16 px-6 py-8 md:px-8 md:py-12">
    <HomeHero />
    <Suspense fallback={<div className="space-y-6"><h2 className="text-headline-3">Gợi ý cho bạn</h2><ProductGridSkeleton /></div>}>
      <HomeCollections />
    </Suspense>
    <aside className="flex flex-col gap-6 rounded-2xl border border-border bg-surface-2 p-6 sm:flex-row sm:items-center sm:justify-between md:p-8"><div className="max-w-prose-narrow space-y-2"><h2 className="text-headline-4">Mua sắm rõ ràng, từ đầu đến sau mua.</h2><p className="text-[15px] text-muted-foreground">Tìm hiểu cách đặt hàng, theo dõi giao hàng và gửi yêu cầu trả hàng.</p></div><Link href="/help" className="focus-ring inline-flex min-h-11 shrink-0 items-center gap-2 rounded-lg text-[14px] underline underline-offset-4">Trung tâm trợ giúp<ArrowRight className="size-4" aria-hidden /></Link></aside>
  </main>;
}

async function HomeCollections() {
  const [categories, newest, promotions] = await Promise.allSettled([
    storefrontServer.categories(), storefrontServer.search({ sort: "newest", size: 100, in_stock: false }),
    storefrontServer.search({ has_promotion: true, sort: "newest", size: 100, in_stock: false }),
  ]);
  const sections = [
    { title: "Gợi ý cho bạn", description: "Một vài lựa chọn cho góc công nghệ của bạn.", result: newest, href: "/search?in_stock=false" },
    { title: "Ưu đãi đang diễn ra", description: "Xem giá và ưu đãi của từng sản phẩm trước khi chọn mua.", result: promotions, href: "/search?has_promotion=true&in_stock=false" },
  ];
  return <div className="space-y-16">
    {sections.map(({ title, description, result, href }, index) => { const items = result.status === "fulfilled" ? featuredProducts(result.value.items) : []; return <div key={title} className="space-y-16">
      <section className="space-y-6">
        <div className="flex flex-wrap items-end justify-between gap-4"><div className="space-y-2"><h2 className="text-headline-3">{title}</h2><p className="text-[14px] text-muted-foreground">{description}</p></div><Link href={href} className="focus-ring inline-flex min-h-11 items-center gap-2 rounded-lg text-[14px] underline underline-offset-4">Xem tất cả<ArrowRight className="size-4" aria-hidden /></Link></div>
        {result.status === "rejected" ? <PublicError failure={publicFailure(result.reason)} message="Chưa tải được sản phẩm trong mục này. Vui lòng thử lại." /> : items.length ? <ProductGrid items={items} /> : <EmptyState icon={PackageSearch} title="Chưa có sản phẩm trong mục này" description="Khám phá các mục khác trong cửa hàng." action={<Link href="/search?in_stock=false" className="focus-ring inline-flex min-h-11 items-center rounded-lg underline">Xem sản phẩm</Link>} />}
      </section>
      {index === 0 && <section className="space-y-6">
        <div className="space-y-2"><h2 className="text-headline-3">Bạn đang tìm gì?</h2><p className="text-[14px] text-muted-foreground">Bắt đầu từ danh mục phù hợp với nhu cầu của bạn.</p></div>
        {categories.status === "rejected" ? <PublicError failure={publicFailure(categories.reason)} message="Chưa tải được danh mục. Bạn vẫn có thể khám phá sản phẩm." /> : navigationCategories(categories.value.categories).length ? <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">{navigationCategories(categories.value.categories).map((category) => <Link key={category.category_id} href={`/c/${encodeURIComponent(category.slug)}`} className="focus-ring group flex items-center justify-between gap-3 rounded-xl border border-border bg-surface-2 p-6 hover:bg-surface-3"><div className="min-w-0 space-y-2"><h3 className="break-words text-headline-5">{category.name}</h3>{category.product_count_updated_at && <p className="text-caption text-muted-foreground">{category.product_count.toLocaleString("vi-VN")} sản phẩm</p>}</div><ArrowRight className="size-4 shrink-0 text-muted-foreground" aria-hidden /></Link>)}</div> : <EmptyState icon={PackageSearch} title="Danh mục đang được cập nhật" description="Bạn vẫn có thể tìm sản phẩm theo tên." action={<Link href="/search?in_stock=false" className="focus-ring inline-flex min-h-11 items-center rounded-lg underline">Tìm sản phẩm</Link>} />}
      </section>}
    </div>; })}
  </div>;
}
