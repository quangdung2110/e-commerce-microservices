import Link from "next/link";

import { Logo } from "@/components/layout/logo";
import { footerColumns } from "@/lib/help-content";

export function Footer() {
  return (
    <footer className="mt-auto border-t border-border bg-surface-2">
      <div className="mx-auto w-full max-w-main px-6 py-14 md:px-8">
        <div className="grid gap-10 md:grid-cols-[1.5fr_repeat(3,1fr)]">
          <div className="space-y-4">
            <Logo />
            <p className="max-w-prose-narrow text-[14px] leading-relaxed text-muted-foreground">
              Khám phá công nghệ phù hợp với bạn. Thông tin sản phẩm, giá và
              đơn hàng rõ ràng ở từng bước mua sắm.
            </p>
          </div>

          {footerColumns.map((column) => (
            <div key={column.title} className="space-y-3">
              <h2 className="text-caption font-medium uppercase tracking-wide text-muted-foreground">
                {column.title}
              </h2>
              <ul className="space-y-2">
                {column.links.map(({ label, href }) => (
                  <li key={href}>
                    <Link
                      href={href}
                      className="focus-ring inline-flex min-h-11 items-center rounded-sm text-[14px] text-foreground-secondary transition-colors hover:text-clay-strong"
                    >
                      {label}
                    </Link>
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </div>

        <div className="mt-12 flex flex-col items-center justify-between gap-3 border-t border-border pt-6 text-caption text-muted-foreground md:flex-row">
          <p>© {new Date().getFullYear()} TechNova. Bảo lưu mọi quyền.</p>
          <Link href="/help" className="focus-ring inline-flex min-h-11 items-center rounded-sm underline underline-offset-4">Hướng dẫn mua sắm và hỗ trợ</Link>
        </div>
      </div>
    </footer>
  );
}
