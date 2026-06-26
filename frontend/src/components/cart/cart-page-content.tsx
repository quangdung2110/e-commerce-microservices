"use client";

import { useEffect } from "react";
import { ChevronRight, Home, ShoppingCart, Loader2 } from "lucide-react";
import { useCart } from "@/hooks/use-cart";
import { useUpdateCartItem, useRemoveFromCart } from "@/hooks/use-cart";
import { useCartStore } from "@/store/useCartStore";
import { CartItemCard, CartItemSkeleton } from "./cart-item";
import { CartSummary } from "./cart-summary";
import { PriceChangeBanner } from "./price-change-banner";
import { EmptyCart } from "./empty-cart";

export function CartPageContent() {
  // Fetch cart data — will sync to Zustand automatically
  const { data, isLoading, isError, error } = useCart();
  const items = useCartStore((s) => s.items);
  const totalItems = useCartStore((s) => s.totalItems);
  const errorMessage = useCartStore((s) => s.error);

  const updateMutation = useUpdateCartItem();
  const removeMutation = useRemoveFromCart();

  const handleUpdateQuantity = (skuId: string, quantity: number) => {
    updateMutation.mutate({ skuId, quantity });
  };

  const handleRemove = (skuId: string) => {
    removeMutation.mutate(skuId);
  };

  const handleCheckout = () => {
    // TODO: Navigate to checkout page
    // router.push("/checkout");
  };

  // ── Loading State ──
  if (isLoading) {
    return (
      <div className="flex-1 container mx-auto px-4 py-8 max-w-6xl relative z-10">
        {/* Breadcrumb skeleton */}
        <div className="flex items-center gap-2 text-sm text-white/50 font-mono mb-8">
          <Home className="h-4 w-4" />
          <ChevronRight className="h-3 w-3 opacity-50" />
          <span>Giỏ hàng</span>
        </div>

        <h1 className="text-3xl font-black tracking-tight mb-8 text-white">
          Giỏ hàng của bạn
        </h1>

        <div className="grid grid-cols-1 lg:grid-cols-3 gap-8">
          <div className="lg:col-span-2 space-y-4">
            {Array.from({ length: 3 }).map((_, i) => (
              <CartItemSkeleton key={i} />
            ))}
          </div>
          <div className="lg:col-span-1">
            <div className="rounded-2xl border border-white/10 bg-[#0c0c0f] p-6 space-y-4 shadow-[0_0_0_1px_rgba(255,255,255,0.04)_inset]">
              <div className="h-5 bg-white/5 animate-pulse rounded w-1/3" />
              <div className="space-y-3">
                <div className="h-4 bg-white/5 animate-pulse rounded w-full" />
                <div className="h-4 bg-white/5 animate-pulse rounded w-3/4" />
              </div>
              <div className="h-12 bg-white/5 animate-pulse rounded-xl" />
            </div>
          </div>
        </div>
      </div>
    );
  }

  // ── Error State ──
  if (isError) {
    return (
      <div className="flex-1 container mx-auto px-4 py-8 max-w-6xl relative z-10">
        <div className="flex flex-col items-center justify-center py-20">
          <div className="h-16 w-16 rounded-full bg-destructive/10 flex items-center justify-center mb-4">
            <Loader2 className="h-8 w-8 text-destructive animate-spin" />
          </div>
          <h2 className="text-xl font-bold mb-2 text-white/90">
            Không thể tải giỏ hàng
          </h2>
          <p className="text-white/50 text-center max-w-md mb-6 font-mono text-sm">
            {errorMessage ||
              "Đã xảy ra lỗi khi tải giỏ hàng. Vui lòng thử lại sau."}
          </p>
        </div>
      </div>
    );
  }

  // ── Empty Cart State ──
  if (!items || items.length === 0) {
    return (
      <div className="flex-1 container mx-auto px-4 py-8 max-w-6xl relative z-10">
        <div className="flex items-center gap-2 text-sm text-white/50 font-mono mb-8">
          <Home className="h-4 w-4" />
          <ChevronRight className="h-3 w-3 opacity-50" />
          <span className="font-medium text-white/90">Giỏ hàng</span>
        </div>
        <EmptyCart />
      </div>
    );
  }

  // ── Cart with Items ──
  return (
    <div className="flex-1 container mx-auto px-4 py-8 max-w-6xl relative z-10">
      {/* Breadcrumb */}
      <div className="flex items-center gap-2 text-sm text-white/50 font-mono mb-8">
        <Home className="h-4 w-4" />
        <ChevronRight className="h-3 w-3 opacity-50" />
        <span className="font-medium text-white/90">Giỏ hàng</span>
      </div>

      {/* Page Title */}
      <div className="flex items-center gap-3 mb-8">
        <ShoppingCart className="h-7 w-7 text-forest-bright" />
        <h1 className="text-3xl font-black tracking-tight text-white">
          Giỏ hàng của bạn
        </h1>
        <span className="text-sm text-white/50 font-mono font-bold mt-2">
          ({totalItems} sản phẩm)
        </span>
      </div>

      {/* Price Change Banner — shows on F5/refresh when prices differ */}
      <div className="mb-6">
        <PriceChangeBanner />
      </div>

      {/* Cart Grid */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-8">
        {/* Cart Items */}
        <div className="lg:col-span-2 space-y-3">
          {items.map((item) => (
            <CartItemCard
              key={item.sku_id}
              item={item}
              onUpdateQuantity={handleUpdateQuantity}
              onRemove={handleRemove}
              isUpdating={
                updateMutation.isPending &&
                updateMutation.variables?.skuId === item.sku_id
              }
            />
          ))}
        </div>

        {/* Cart Summary */}
        <div className="lg:col-span-1">
          <CartSummary
            onCheckout={handleCheckout}
            isLoading={updateMutation.isPending}
          />
        </div>
      </div>
    </div>
  );
}
