"use client";

import Link from "next/link";
import { Bell } from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { buttonVariants } from "@/components/ui/button";
import { useUnreadCount } from "@/hooks/use-notifications";
import { useAuthStore } from "@/store/useAuthStore";
import { cn } from "@/lib/utils";

/**
 * Chuông thông báo trên header. Chỉ hiện khi đã đăng nhập. Huy hiệu là gợi ý từ
 * Redis (có thể cũ vài giây); lỗi hay chưa tải xong đều không hiện số chứ không hiện
 * số sai.
 */
export function NotificationBell() {
  const status = useAuthStore((state) => state.status);
  const { data } = useUnreadCount();
  if (status !== "authenticated") return null;

  const count = data?.count ?? 0;
  return (
    <Link
      href="/notifications"
      aria-label={count > 0 ? `Thông báo, ${count} chưa đọc` : "Thông báo"}
      className={cn(buttonVariants({ variant: "ghost", size: "icon" }), "relative")}
    >
      <Bell className="size-5" aria-hidden />
      {count > 0 ? (
        <Badge variant="clay" aria-hidden className="absolute right-1 top-1 min-w-4 justify-center px-1 py-0 text-micro">
          {count > 99 ? "99+" : count}
        </Badge>
      ) : null}
    </Link>
  );
}
