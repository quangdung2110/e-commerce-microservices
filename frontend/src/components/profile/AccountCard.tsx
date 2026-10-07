"use client";

import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { useAuth } from "@/hooks/use-auth";
import { useProfile } from "@/hooks/use-user";
import { formatDateTime } from "@/lib/format";

/**
 * Tóm tắt tài khoản: những thông tin người dùng chỉ đọc chứ không sửa ở đây.
 *
 * Email không nằm trong access token (Auth Service phát `sub`, `sid`, `roles`)
 * và cũng không có trong profile của User Service, nên giá trị hiển thị là địa
 * chỉ người dùng đã dùng để đăng nhập trên chính trình duyệt này. Nếu không có,
 * ô này được ẩn thay vì hiện một chỗ trống khó hiểu.
 */
export function AccountCard() {
  const { email, userId, roles } = useAuth();
  const { data: profile, isPending } = useProfile();

  return (
    <Card>
      <CardHeader>
        <CardTitle>Tài khoản</CardTitle>
        <CardDescription>Thông tin định danh, không thể chỉnh sửa trực tiếp.</CardDescription>
      </CardHeader>

      <CardContent>
        <dl className="divide-y divide-border">
          {email && <Row label="Email đăng nhập" value={email} />}

          <Row
            label="Ngày tham gia"
            value={
              isPending ? (
                <Skeleton className="h-5 w-40" />
              ) : profile ? (
                formatDateTime(profile.created_at)
              ) : (
                "—"
              )
            }
          />

          {roles.length > 0 && (
            <Row label="Vai trò" value={roles.join(", ")} />
          )}

          <Row
            label="Mã tài khoản"
            value={
              <span className="font-mono text-caption break-all">{userId ?? "—"}</span>
            }
          />
        </dl>
      </CardContent>
    </Card>
  );
}

function Row({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="grid gap-1 py-3 first:pt-0 last:pb-0 sm:grid-cols-[180px_1fr] sm:gap-4">
      <dt className="text-[14px] text-muted-foreground">{label}</dt>
      <dd className="min-w-0 text-[15px] text-foreground">{value}</dd>
    </div>
  );
}
