"use client";

import { ShoppingBag } from "lucide-react";
import { Button } from "@/components/ui/button";
import Link from "next/link";

interface EmptyCartProps {
  /** Optional custom message */
  title?: string;
  description?: string;
}

export function EmptyCart({
  title = "Giỏ hàng trống",
  description = "Bạn chưa có sản phẩm nào trong giỏ hàng.",
}: EmptyCartProps) {
  return (
    <div className="flex flex-col items-center justify-center py-20 px-4">
      {/* Icon */}
      <div className="h-32 w-32 rounded-full bg-[#0c0c0f] border border-white/10 shadow-[0_0_0_1px_rgba(255,255,255,0.04)_inset] flex items-center justify-center mb-6 relative">
        <div className="absolute inset-0 bg-[radial-gradient(circle_at_center,_var(--tw-gradient-stops))] from-white/5 to-transparent rounded-full" />
        <ShoppingBag className="h-12 w-12 text-white/20 relative z-10" />
      </div>

      {/* Title */}
      <h2 className="text-2xl font-black text-white/90 mb-2 uppercase tracking-widest font-mono">
        {title}
      </h2>

      {/* Description */}
      <p className="text-white/50 text-center max-w-md mb-8 font-mono">
        {description}
      </p>

      {/* CTA */}
      <Link href="/">
        <Button 
          variant="secondary"
          size="lg" 
          className="rounded-2xl px-8 font-black uppercase tracking-widest font-mono shadow-[0_0_20px_rgba(40,200,64,0.3)] hover:shadow-[0_0_30px_rgba(40,200,64,0.6)]"
        >
          Mua sắm ngay
        </Button>
      </Link>
    </div>
  );
}
