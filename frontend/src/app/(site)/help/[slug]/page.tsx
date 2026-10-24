import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { ArrowLeft, ArrowRight } from "lucide-react";
import { findHelpArticle, helpArticles } from "@/lib/help-content";
import { buttonVariants } from "@/components/ui/button";

type Props = { params: Promise<{ slug: string }> };

export const dynamicParams = false;
export function generateStaticParams() { return helpArticles.map(({ slug }) => ({ slug })); }
export async function generateMetadata({ params }: Props): Promise<Metadata> {
  const article = findHelpArticle((await params).slug);
  return { title: article?.title ?? "Không tìm thấy hướng dẫn", description: article?.description };
}

export default async function HelpArticlePage({ params }: Props) {
  const article = findHelpArticle((await params).slug);
  if (!article) notFound();
  return <main className="mx-auto w-full max-w-medium space-y-8 px-6 py-10 md:px-8 md:py-14">
    <Link href="/help" className="focus-ring inline-flex min-h-11 items-center gap-2 rounded-lg text-[14px] text-muted-foreground hover:text-foreground"><ArrowLeft className="size-4" aria-hidden />Trung tâm trợ giúp</Link>
    <div className="grid items-start gap-10 lg:grid-cols-[220px_1fr] lg:gap-16">
      <nav aria-label="Các chủ đề trợ giúp" className="flex gap-1 overflow-x-auto border-b border-border pb-3 lg:sticky lg:top-40 lg:flex-col lg:border-b-0 lg:pb-0">{helpArticles.map(({ slug, title }) => <Link key={slug} href={`/help/${slug}`} aria-current={slug === article.slug ? "page" : undefined} className={`focus-ring flex min-h-11 shrink-0 items-center rounded-lg px-3 text-[14px] lg:shrink hover:bg-accent ${slug === article.slug ? "bg-accent font-medium text-foreground" : "text-muted-foreground"}`}>{title}</Link>)}</nav>
      <article className="min-w-0 max-w-prose-wide space-y-10">
        <header className="space-y-4"><h1 className="text-headline-1">{article.title}</h1><p className="text-body-2 text-muted-foreground">{article.description}</p></header>
        {article.sections.map((section) => <section key={section.title} className="space-y-4"><h2 className="text-headline-5">{section.title}</h2>{section.paragraphs?.map((paragraph) => <p key={paragraph} className="text-[15px] leading-relaxed text-foreground-secondary">{paragraph}</p>)}{section.steps && <ol className="list-decimal space-y-3 pl-5 text-[15px] leading-relaxed text-foreground-secondary">{section.steps.map((step) => <li key={step} className="pl-2">{step}</li>)}</ol>}</section>)}
        <div className="space-y-4 border-t border-border pt-8"><Link href={article.action.href} className={buttonVariants({ variant: "default" })}>{article.action.label}<ArrowRight className="size-4" aria-hidden /></Link><p className="text-[14px] text-muted-foreground">Cần tìm nội dung khác? <Link href="/help" className="focus-ring rounded-sm text-foreground underline underline-offset-4">Xem tất cả hướng dẫn</Link>.</p></div>
      </article>
    </div>
  </main>;
}
