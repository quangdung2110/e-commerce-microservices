import Image from "next/image";
import Link from "next/link";
import { ArrowRight } from "lucide-react";
import { buttonVariants } from "@/components/ui/button";

export function HomeHero() {
  return <header className="grid items-center gap-6 md:grid-cols-2 md:gap-12">
    <div className="space-y-5">
      <p className="text-caption uppercase tracking-wide text-muted-foreground">TechNova · Công nghệ mỗi ngày</p>
      <h1 className="max-w-prose-title text-headline-1">Công nghệ cho mỗi ngày.</h1>
      <p className="max-w-prose-narrow text-body-2 text-muted-foreground">Từ góc làm việc đến những phút thư giãn. Khám phá sản phẩm và chọn điều phù hợp với bạn.</p>
      <div className="flex flex-wrap items-center gap-x-6 gap-y-3">
        <Link href="/search?in_stock=false" className={buttonVariants({ variant: "clay" })}>Khám phá sản phẩm<ArrowRight className="size-4" aria-hidden /></Link>
        <Link href="/search?has_promotion=true&in_stock=false" className="focus-ring inline-flex min-h-11 items-center rounded-lg text-[14px] underline underline-offset-4">Xem ưu đãi</Link>
      </div>
    </div>
    <figure className="overflow-hidden rounded-2xl border border-border bg-surface-2">
      <Image src="/images/technology-desk.jpg" alt="Laptop, tai nghe, điện thoại và đồng hồ trên bàn làm việc" width={1400} height={933} sizes="(min-width: 1192px) 540px, (min-width: 768px) 45vw, 100vw" preload className="aspect-[2/1] w-full object-cover md:aspect-[3/2]" />
      <figcaption className="flex flex-wrap items-center justify-between gap-2 px-4 py-3 text-caption text-muted-foreground"><span>Cảm hứng cho góc công nghệ của bạn.</span><a href="https://unsplash.com/photos/_aXa21cf7rY" target="_blank" rel="noreferrer" className="focus-ring rounded-sm underline underline-offset-4">Ảnh: Christopher Gower</a></figcaption>
    </figure>
  </header>;
}
