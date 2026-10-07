"use client";

import Link from "next/link";
import {
  LayoutDashboard,
  LogOut,
  MapPin,
  Bell,
  ShoppingBag,
  ShieldCheck,
  SlidersHorizontal,
  Star,
  User,
} from "lucide-react";

import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { buttonVariants } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { useAuth } from "@/hooks/use-auth";
import { useProfile } from "@/hooks/use-user";
import { ROLES } from "@/lib/auth/roles";
import { cn } from "@/lib/utils";

const LINKS = [
  { href: "/orders", label: "Đơn hàng của tôi", icon: ShoppingBag },
  { href: "/reviews", label: "Đánh giá của tôi", icon: Star },
  { href: "/notifications", label: "Thông báo", icon: Bell },
  { href: "/profile", label: "Hồ sơ cá nhân", icon: User },
  { href: "/profile/addresses", label: "Sổ địa chỉ", icon: MapPin },
  { href: "/profile/preferences", label: "Tuỳ chọn thông báo", icon: SlidersHorizontal },
  { href: "/profile/security", label: "Bảo mật", icon: ShieldCheck },
];

export function AccountMenu() {
  const { status, isAuthenticated, email, logout, hasRole } = useAuth();
  const { data: profile } = useProfile();

  // Trong lúc chưa biết phiên còn hiệu lực hay không, giữ chỗ để header không nhảy.
  if (status === "loading") {
    return <Skeleton className="size-11 rounded-lg" />;
  }

  if (!isAuthenticated) {
    return (
      <Link
        href="/login"
        className={cn(buttonVariants({ variant: "outline", size: "sm" }))}
      >
        Đăng nhập
      </Link>
    );
  }

  const displayName = profile?.display_name?.trim() || email || "Tài khoản";
  const initial = displayName.charAt(0).toUpperCase();

  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        aria-label="Menu tài khoản"
        className={cn(buttonVariants({ variant: "ghost", size: "icon" }))}
      >
        <span
          aria-hidden
          className="grid size-7 place-content-center rounded-full bg-surface-3 text-[13px] font-medium text-foreground-secondary"
        >
          {initial}
        </span>
      </DropdownMenuTrigger>

      {/* DropdownMenuContent tự bọc Portal + Positioner, không bọc thêm ở đây. */}
      <DropdownMenuContent align="end" className="w-60">
        <div className="mb-1 border-b border-border px-2.5 pb-2.5 pt-1">
          <p className="truncate text-[14px] font-medium text-foreground">
            {displayName}
          </p>
          {email && (
            <p className="truncate text-caption text-muted-foreground">{email}</p>
          )}
        </div>

        {LINKS.map(({ href, label, icon: Icon }) => (
          <DropdownMenuItem key={href} render={<Link href={href} />}>
            <Icon className="size-4" aria-hidden />
            {label}
          </DropdownMenuItem>
        ))}

        {/* Chỉ ẩn/hiện lối vào; quyền thật do gateway và service kiểm tra. */}
        {hasRole(ROLES.ADMIN) && (
          <>
            <DropdownMenuSeparator />
            <DropdownMenuItem render={<Link href="/admin" />}>
              <LayoutDashboard className="size-4" aria-hidden />
              Trang quản trị
            </DropdownMenuItem>
          </>
        )}

        <DropdownMenuSeparator />

        <DropdownMenuItem variant="destructive" onClick={() => void logout()}>
          <LogOut className="size-4" aria-hidden />
          Đăng xuất
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
