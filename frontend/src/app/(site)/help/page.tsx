import type { Metadata } from "next";
import Link from "next/link";
import { ArrowRight, PackageSearch, UserRound, ShoppingBag } from "lucide-react";
import { helpArticles } from "@/lib/help-content";

export const metadata: Metadata = { title: "Trung tâm trợ giúp", description: "Hướng dẫn mua sắm, giao hàng, trả hàng, thanh toán và quản lý tài khoản TechNova." };

export default function HelpPage() {
  return <main className="mx-auto w-full max-w-medium space-y-12 px-6 py-10 md:px-8 md:py-14">
    <header className="max-w-prose-narrow space-y-4">
      <p className="text-caption uppercase tracking-wide text-muted-foreground">TechNova · Luôn rõ ràng ở mỗi bước</p>
      <h1 className="text-headline-1">Chúng tôi có thể giúp gì cho bạn?</h1>
      <p className="text-body-2 text-muted-foreground">Tìm hướng dẫn cho việc mua sắm, đơn hàng và tài khoản của bạn.</p>
    </header>
    <section aria-label="Lối tắt thường dùng" className="grid gap-4 sm:grid-cols-3">
      {[{ href: "/orders", label: "Theo dõi đơn hàng", description: "Thanh toán, giao hàng và yêu cầu trả.", icon: ShoppingBag }, { href: "/profile", label: "Quản lý tài khoản", description: "Hồ sơ, địa chỉ và bảo mật.", icon: UserRound }, { href: "/search?in_stock=false", label: "Tìm sản phẩm", description: "Chọn phân loại và xem thông tin.", icon: PackageSearch }].map(({ href, label, description, icon: Icon }) => <Link key={href} href={href} className="focus-ring space-y-3 rounded-xl border border-border bg-surface-2 p-6 hover:bg-surface-3"><Icon className="size-5 text-muted-foreground" aria-hidden /><h2 className="text-headline-5">{label}</h2><p className="text-[14px] text-muted-foreground">{description}</p></Link>)}
    </section>
    <section className="space-y-6"><h2 className="text-headline-3">Hướng dẫn và thông tin</h2><div className="grid gap-4 md:grid-cols-2">{helpArticles.map((article) => <Link key={article.slug} href={`/help/${article.slug}`} className="focus-ring group flex items-start justify-between gap-5 rounded-xl border border-border bg-surface-overlay p-6 hover:bg-surface-2"><div className="space-y-2"><h3 className="text-headline-5">{article.title}</h3><p className="text-[14px] leading-relaxed text-muted-foreground">{article.description}</p></div><ArrowRight className="mt-1 size-5 shrink-0 text-muted-foreground" aria-hidden /></Link>)}</div></section>
  </main>;
}
