import Link from "next/link";
import {
  Boxes,
  ClipboardList,
  Megaphone,
  MessageSquareWarning,
  PackagePlus,
  PackageSearch,
  type LucideIcon,
} from "lucide-react";

import { PageHeader } from "@/components/admin/page-header";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";

const SHORTCUTS: Array<{
  title: string;
  description: string;
  href?: string;
  icon: LucideIcon;
}> = [
  {
    title: "Xem danh sách sản phẩm",
    description: "Lọc sản phẩm theo trạng thái và danh mục.",
    href: "/admin/products",
    icon: PackageSearch,
  },
  {
    title: "Tạo sản phẩm",
    description: "Tạo sản phẩm nháp cùng SKU đầu tiên.",
    href: "/admin/products/new",
    icon: PackagePlus,
  },
  {
    title: "Nhập hàng",
    description: "Ghi nhận tồn ban đầu hoặc phiếu nhận từ nhà cung cấp.",
    href: "/admin/inventory/receipts/new",
    icon: Boxes,
  },
  {
    title: "Tìm đơn hàng",
    description: "Sẽ có trong bước vận hành đơn hàng.",
    icon: ClipboardList,
  },
  {
    title: "Quản lý khuyến mãi",
    description: "Tạo campaign, voucher và giảm giá theo sản phẩm.",
    href: "/admin/promotions",
    icon: Megaphone,
  },
  {
    title: "Hàng đợi kiểm duyệt",
    description: "Sẽ có trong bước kiểm duyệt đánh giá.",
    icon: MessageSquareWarning,
  },
];

export default function AdminOverviewPage() {
  return (
    <div className="space-y-10">
      <PageHeader
        title="Tổng quan quản trị"
        description="Đi tới công việc cần xử lý. Các khu vực sẽ xuất hiện dần khi API và màn hình tương ứng hoàn thành."
      />

      <section aria-labelledby="quick-actions-heading" className="space-y-5">
        <div className="space-y-1">
          <h2 id="quick-actions-heading" className="text-headline-5 font-serif font-normal">
            Lối tắt
          </h2>
          <p className="text-caption text-muted-foreground">
            Không hiển thị số liệu ước đoán khi chưa có API thống kê.
          </p>
        </div>
        <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
          {SHORTCUTS.map(({ title, description, href, icon: Icon }) => {
            const content = (
              <>
                <div className="flex items-start justify-between gap-4">
                  <span className="grid size-11 place-content-center rounded-lg bg-surface-3 text-foreground-secondary">
                    <Icon className="size-5" aria-hidden />
                  </span>
                  {!href ? <Badge>Sắp có</Badge> : null}
                </div>
                <div className="space-y-1.5">
                  <h3 className="text-headline-6 font-serif font-normal">{title}</h3>
                  <p className="text-caption text-muted-foreground">{description}</p>
                </div>
              </>
            );
            const classes = cn(
              "flex min-h-44 flex-col justify-between gap-6 rounded-xl border border-border bg-surface-overlay p-6 text-left",
              href && "focus-ring transition-colors duration-200 hover:border-border-strong hover:bg-surface-2",
              !href && "opacity-70"
            );
            return href ? (
              <Link key={title} href={href} className={classes}>
                {content}
              </Link>
            ) : (
              <div key={title} aria-disabled="true" className={classes}>
                {content}
              </div>
            );
          })}
        </div>
      </section>
    </div>
  );
}
