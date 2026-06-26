"use client";

import { ShoppingCart, User, Bell } from "lucide-react";
import { SearchOmnibox } from "@/components/search/search-omnibox";
import { MegaMenu } from "./mega-menu";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import Link from "next/link";
import { useCart } from "@/hooks/use-cart";

export function Header() {
  // Lightweight cart count fetch — staleTime bắt buộc tránh gọi liên tục
  const { data: cartData } = useCart();
  const cartCount = cartData?.total_items ?? 0;

  return (
    <header className="sticky top-0 z-40 w-full border-b border-white/[0.08] bg-black/70 backdrop-blur-xl supports-[backdrop-filter]:bg-black/50">
      {/* Top micro-bar */}
      <div className="w-full bg-[#0a140f] border-b border-forest/20 text-forest-bright py-1.5 px-4 text-xs font-mono font-medium text-center tracking-wide">
        <span className="animate-pulse inline-block w-2 h-2 rounded-full bg-forest-bright mr-2 align-middle"></span>
        Khuyến mãi ra mắt: Giảm thêm 5% cho tất cả đơn hàng đầu tiên!
      </div>

      {/* Main Header */}
      <div className="container mx-auto px-4 h-20 flex items-center justify-between gap-8">
        {/* Logo */}
        <Link href="/" className="flex-shrink-0 flex items-center gap-2 cursor-pointer group">
          <div className="h-10 w-10 rounded-[5px] bg-[#0c0c0f] border border-white/10 flex items-center justify-center shadow-[0_0_15px_rgba(255,255,255,0.05)] group-hover:border-forest/50 group-hover:shadow-[0_0_20px_rgba(40,200,64,0.2)] transition-all duration-300">
            <span className="text-xl font-black text-white group-hover:text-forest-bright transition-colors duration-300">T</span>
          </div>
          <span className="text-2xl font-black tracking-tighter text-white/90 group-hover:text-white transition-colors duration-300">
            TechNova
          </span>
        </Link>

        {/* Search Bar */}
        <div className="flex-1 max-w-3xl">
          <SearchOmnibox />
        </div>

        {/* Actions */}
        <div className="flex items-center gap-2 flex-shrink-0">
          <Button variant="ghost" size="icon" className="rounded-full hover:bg-white/[0.06] hover:text-white transition-all duration-300 text-white/55">
            <Bell className="h-5 w-5" />
          </Button>
          <Button variant="ghost" size="icon" className="rounded-full hover:bg-white/[0.06] hover:text-white transition-all duration-300 text-white/55">
            <User className="h-5 w-5" />
          </Button>
          <Link href="/cart">
            <Button variant="ghost" size="icon" className="rounded-full hover:bg-white/[0.06] text-white/55 hover:text-white relative group transition-all duration-300">
              <ShoppingCart className="h-5 w-5 group-hover:text-forest-bright transition-colors duration-300" />
              {cartCount > 0 && (
                <>
                  <span className="absolute -top-1 -right-1 h-5 w-5 animate-ping rounded-full bg-forest-bright opacity-40"></span>
                  <Badge className="absolute -top-1 -right-1 min-w-[20px] h-5 flex items-center justify-center p-0 text-[10px] bg-forest-bright text-black hover:bg-forest transition-colors">
                    {cartCount > 99 ? "99+" : cartCount}
                  </Badge>
                </>
              )}
            </Button>
          </Link>
        </div>
      </div>

      {/* Navigation / Mega Menu */}
      <div className="container mx-auto px-4">
        <MegaMenu />
      </div>
    </header>
  );
}
