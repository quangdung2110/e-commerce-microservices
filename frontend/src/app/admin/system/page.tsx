import Link from "next/link";
import { PageHeader } from "@/components/admin/page-header";
export default function Page() {
  return (
    <div className="space-y-8">
      <PageHeader
        title="Hệ thống"
        description="Công cụ vận hành dành cho quản trị viên."
      />
      <div className="grid gap-5 sm:grid-cols-2">
        {[
          [
            "search-reindex",
            "Tái lập chỉ mục Search",
            "Khởi tạo, theo dõi và quản lý lượt reindex.",
          ],
          [
            "account-deletion",
            "Xoá tài khoản",
            "Yêu cầu xoá theo mã tài khoản đã xác minh.",
          ],
        ].map(([path, title, description]) => (
          <Link
            key={path}
            className="focus-ring space-y-3 rounded-xl border border-border bg-surface-overlay p-6 hover:bg-surface-2"
            href={`/admin/system/${path}`}
          >
            <h2 className="text-headline-5 font-serif font-normal">{title}</h2>
            <p className="text-caption text-muted-foreground">{description}</p>
          </Link>
        ))}
      </div>
    </div>
  );
}
