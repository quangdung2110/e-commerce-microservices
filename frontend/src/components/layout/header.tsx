"use client";

import Link from "next/link";
import { ShoppingCart } from "lucide-react";

import { SearchOmnibox } from "@/components/search/search-omnibox";
import { Badge } from "@/components/ui/badge";
import { buttonVariants } from "@/components/ui/button";
import { AccountMenu } from "@/components/layout/account-menu";
import { NotificationBell } from "@/components/notifications/notification-bell";
import { Logo } from "@/components/layout/logo";
import { ThemeToggle } from "@/components/layout/theme-toggle";
import { MegaMenu } from "./mega-menu";
import { useCart } from "@/hooks/use-cart";
import { useAuthStore } from "@/store/useAuthStore";
import { cn } from "@/lib/utils";

export function Header() {
  const status = useAuthStore((state) => state.status);
  const { data: cartData } = useCart();
  const cartCount = status === "authenticated" ? cartData?.total_units ?? 0 : 0;

  return (
    <header className="sticky top-0 z-40 w-full border-b border-border bg-background/85 backdrop-blur-md">
      <div className="mx-auto flex h-16 w-full max-w-main items-center gap-3 px-4 sm:px-6 md:gap-6 md:px-8">
        <Logo className="[&>span:last-child]:hidden sm:[&>span:last-child]:block" />

        <div className="hidden min-w-0 flex-1 md:block">
          <SearchOmnibox />
        </div>

        <div className="ml-auto flex shrink-0 items-center gap-1">
          <ThemeToggle />

          <NotificationBell />

          <Link
            href="/cart"
            aria-label={
              cartCount > 0 ? `Giỏ hàng, ${cartCount} sản phẩm` : "Giỏ hàng"
            }
            className={cn(
              buttonVariants({ variant: "ghost", size: "icon" }),
              "relative"
            )}
          >
            <ShoppingCart className="size-5" aria-hidden />
            {cartCount > 0 && (
              <Badge
                variant="clay"
                aria-hidden
                className="absolute right-1 top-1 min-w-4 justify-center px-1 py-0 text-micro"
              >
                {cartCount > 99 ? "99+" : cartCount}
              </Badge>
            )}
          </Link>

          <AccountMenu />
        </div>
      </div>

      <div className="mx-auto w-full max-w-main px-6 pb-2 md:hidden"><SearchOmnibox /></div>
      <div className="mx-auto w-full max-w-main px-6 md:px-8">
        <MegaMenu />
      </div>
    </header>
  );
}
