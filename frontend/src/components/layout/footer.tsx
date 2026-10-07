import Link from "next/link";

import { Logo } from "@/components/layout/logo";

const COLUMNS = [
  {
    title: "Về chúng tôi",
    links: ["Giới thiệu", "Tuyển dụng", "Tin tức", "Liên hệ"],
  },
  {
    title: "Hỗ trợ khách hàng",
    links: ["Trung tâm trợ giúp", "Chính sách đổi trả", "Bảo hành", "Vận chuyển"],
  },
  {
    title: "Chính sách",
    links: ["Điều khoản sử dụng", "Chính sách bảo mật", "Chính sách cookie"],
  },
];

export function Footer() {
  return (
    <footer className="mt-auto border-t border-border bg-surface-2">
      <div className="mx-auto w-full max-w-main px-6 py-14 md:px-8">
        <div className="grid gap-10 md:grid-cols-[1.5fr_repeat(3,1fr)]">
          <div className="space-y-4">
            <Logo />
            <p className="max-w-prose-narrow text-[14px] leading-relaxed text-muted-foreground">
              Nền tảng thương mại điện tử cho sản phẩm công nghệ chính hãng, với
              giá minh bạch và giao hàng nhanh.
            </p>
          </div>

          {COLUMNS.map((column) => (
            <div key={column.title} className="space-y-3">
              <h2 className="text-caption font-medium uppercase tracking-wide text-muted-foreground">
                {column.title}
              </h2>
              <ul className="space-y-2">
                {column.links.map((label) => (
                  <li key={label}>
                    <Link
                      href="/"
                      className="focus-ring rounded-sm text-[14px] text-foreground-secondary transition-colors hover:text-clay-strong"
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
          <p>Đã đăng ký với Bộ Công Thương</p>
        </div>
      </div>
    </footer>
  );
}
