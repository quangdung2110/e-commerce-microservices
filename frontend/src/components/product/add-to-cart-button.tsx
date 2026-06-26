"use client";

import { ShoppingCart, Loader2, Check } from "lucide-react";
import { Button } from "@/components/ui/button";
import { usePDPStore } from "@/store/usePDPStore";
import { useAddToCart } from "@/hooks/use-cart";
import { useToast } from "@/components/ui/toast";

export function AddToCartButton() {
  const selectedSku = usePDPStore((s) => s.getSelectedSku());
  const quantity = usePDPStore((s) => s.quantity);
  const isAddingToCart = usePDPStore((s) => s.isAddingToCart);
  const setIsAddingToCart = usePDPStore((s) => s.setIsAddingToCart);

  const addToCartMutation = useAddToCart();
  const { addToast } = useToast();

  const isOutOfStock =
    !selectedSku ||
    selectedSku.display_quantity <= 0 ||
    selectedSku.sku_status !== "ACTIVE";

  const handleAddToCart = async () => {
    if (!selectedSku || isOutOfStock) return;

    setIsAddingToCart(true);
    try {
      await addToCartMutation.mutateAsync({
        sku_id: selectedSku.sku_id,
        quantity,
      });

      addToast({
        type: "success",
        title: "Đã thêm vào giỏ hàng",
        message: `${selectedSku.sku_code || selectedSku.sku_id} x${quantity}`,
        duration: 3000,
      });
    } catch (error: any) {
      const message =
        error?.response?.data?.message ||
        "Không thể thêm sản phẩm. Vui lòng thử lại.";
      addToast({
        type: "error",
        title: "Thêm thất bại",
        message,
        duration: 5000,
      });
    } finally {
      setIsAddingToCart(false);
    }
  };

  // Out of stock
  if (isOutOfStock) {
    return (
      <Button
        disabled
        className="w-full h-14 rounded-xl text-base font-bold"
        size="lg"
      >
        Hết hàng
      </Button>
    );
  }

  // No SKU selected
  if (!selectedSku) {
    return (
      <Button
        disabled
        className="w-full h-14 rounded-xl text-base font-bold"
        size="lg"
      >
        Vui lòng chọn phân loại
      </Button>
    );
  }

  // Normal add to cart
  return (
    <Button
      variant="secondary"
      className="w-full h-14 rounded-2xl text-base font-black gap-2 uppercase tracking-widest font-mono shadow-[0_0_20px_rgba(40,200,64,0.3)] hover:shadow-[0_0_30px_rgba(40,200,64,0.6)] group relative overflow-hidden"
      size="lg"
      onClick={handleAddToCart}
      disabled={isAddingToCart}
    >
      <div className="absolute inset-0 bg-[radial-gradient(ellipse_at_center,_var(--tw-gradient-stops))] from-white/20 to-transparent opacity-0 group-hover:opacity-100 transition-opacity duration-300" />
      <div className="relative flex items-center gap-2 z-10">
        {isAddingToCart ? (
          <>
            <Loader2 className="h-5 w-5 animate-spin" />
            Đang thêm...
          </>
        ) : addToCartMutation.isSuccess ? (
          <>
            <Check className="h-5 w-5" />
            Đã thêm vào giỏ hàng
          </>
        ) : (
          <>
            <ShoppingCart className="h-5 w-5 group-hover:scale-110 transition-transform duration-300" />
            Thêm vào giỏ hàng
          </>
        )}
      </div>
    </Button>
  );
}
