"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { MapPin, ShieldCheck, SlidersHorizontal, User } from "lucide-react";

import { cn } from "@/lib/utils";

const NAV_ITEMS = [
  { title: "Hồ sơ cá nhân", href: "/profile", icon: User },
  { title: "Sổ địa chỉ", href: "/profile/addresses", icon: MapPin },
  { title: "Tuỳ chọn thông báo", href: "/profile/preferences", icon: SlidersHorizontal },
  { title: "Bảo mật", href: "/profile/security", icon: ShieldCheck },
];

/**
 * Điều hướng giữa các mục cài đặt. Trên mobile là hàng ngang cuộn được chứ
 * không phải dropdown — người dùng cần thấy có những mục gì mà không phải bấm.
 */
export function ProfileSidebar() {
  const pathname = usePathname();

  return (
    <nav
      aria-label="Mục cài đặt tài khoản"
      className="-mx-1 flex gap-1 overflow-x-auto px-1 pb-2 md:mx-0 md:flex-col md:overflow-visible md:px-0 md:pb-0"
    >
      {NAV_ITEMS.map(({ title, href, icon: Icon }) => {
        const isActive = pathname === href;
        return (
          <Link
            key={href}
            href={href}
            aria-current={isActive ? "page" : undefined}
            className={cn(
              "focus-ring relative flex shrink-0 items-center gap-2.5 rounded-lg px-3 py-2 text-[14px] transition-colors duration-200",
              isActive
                ? "bg-accent font-medium text-foreground"
                : "text-muted-foreground hover:bg-accent hover:text-foreground"
            )}
          >
            {/* Thanh clay mảnh là dấu hiệu "đang ở đây", chỉ hiện trên cột dọc. */}
            {isActive && (
              <span
                aria-hidden
                className="absolute inset-y-1.5 left-0 hidden w-0.5 rounded-full bg-clay md:block"
              />
            )}
            <Icon className="size-4 shrink-0" aria-hidden />
            {title}
          </Link>
        );
      })}
    </nav>
  );
}
