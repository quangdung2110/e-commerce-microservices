import { Logo } from "@/components/layout/logo";
import { ThemeToggle } from "@/components/layout/theme-toggle";

/**
 * Khung hai cột của toàn bộ luồng xác thực.
 *
 * Cột phải cố tình là một mặt phẳng màu cát với chữ serif, không ảnh nền, không
 * gradient, không glow: tương phản đến từ chất liệu chứ không từ hiệu ứng.
 */
export default function AuthLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <div className="min-h-svh lg:grid lg:grid-cols-2">
      <div className="relative flex min-h-svh flex-col px-6 py-8 lg:min-h-0 lg:px-16">
        <div className="flex items-center justify-between">
          <Logo />
          <ThemeToggle />
        </div>

        <div className="flex flex-1 items-center justify-center py-12">
          <div className="w-full max-w-[400px]">{children}</div>
        </div>

        <p className="text-center text-caption text-muted-foreground">
          © {new Date().getFullYear()} TechNova
        </p>
      </div>

      <aside className="hidden border-l border-border bg-surface-2 lg:flex lg:flex-col lg:justify-center lg:px-16">
        <div className="max-w-prose-title space-y-8">
          <p className="font-serif text-headline-4 font-light leading-snug text-foreground">
            Mua sắm nên là chuyện nhẹ nhàng — tìm thấy thứ mình cần, biết rõ mình
            trả bao nhiêu, và không phải nghĩ thêm gì nữa.
          </p>

          <div className="flex items-center gap-2" aria-hidden>
            <span className="size-2.5 rounded-full bg-hue-sage" />
            <span className="size-2.5 rounded-full bg-hue-oat" />
            <span className="size-2.5 rounded-full bg-clay" />
            <span className="size-2.5 rounded-full bg-hue-sky" />
          </div>

          <p className="text-[15px] leading-relaxed text-muted-foreground">
            Hơn 12.000 sản phẩm công nghệ chính hãng, giao nhanh trong ngày tại Hà
            Nội và TP. Hồ Chí Minh.
          </p>
        </div>
      </aside>
    </div>
  );
}
