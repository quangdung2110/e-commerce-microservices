"use client";
import Link from "next/link";
import { useQueryClient } from "@tanstack/react-query";
import { Button, buttonVariants } from "@/components/ui/button";
import { ErrorState } from "@/components/ui/error-state";
import { useCommand } from "@/hooks/use-command";
import { cartApi } from "@/lib/api/cart";
import { cartKeys } from "@/hooks/use-cart";
import { canAddSku, pdpQuantity } from "@/lib/product-detail";
import { isRetryable } from "@/lib/api/problem";
import type { DetailSku } from "@/lib/api/product-detail";
import { useAuthStore } from "@/store/useAuthStore";
import { useToast } from "@/components/ui/toast";
import { useEffect } from "react";
import { MobileActionDock } from "@/components/ui/mobile-action-dock";
import { formatVND } from "@/lib/format";

export function AddToCartButton({ sku, quantity, slug, onLockChange }: { sku?: DetailSku; quantity: number; slug: string; onLockChange: (locked: boolean) => void }) {
  const status = useAuthStore((state) => state.status);
  const client = useQueryClient();
  const { addToast } = useToast();
  const command = useCommand({
    mutationFn: (body: { sku_id: string; delta: number }, { commandId }) => cartApi.adjust(body, commandId),
    onSuccess: (result) => {
      void client.invalidateQueries({ queryKey: cartKeys.all });
      addToast({ type: "success", title: "Đã cập nhật giỏ hàng", message: `Giỏ hiện có ${result.quantity} sản phẩm của biến thể này.` });
    },
  });
  const locked = command.isPending || (command.isError && isRetryable(command.error));
  useEffect(() => { onLockChange(locked); }, [locked, onLockChange]);
  const next = `/products/${encodeURIComponent(slug)}${sku ? `?sku=${encodeURIComponent(sku.sku_id)}&quantity=${pdpQuantity(quantity)}` : ""}`;
  const feedback = command.isError ? <><ErrorState error={command.error} />{locked && !command.isPending && <p className="text-caption text-muted-foreground">Kết quả lần gửi trước chưa rõ. Thử lại cùng lựa chọn để tránh thêm hai lần.</p>}</> : null;
  return <MobileActionDock label="Đơn giá" amount={sku ? formatVND(sku.selling_price_vnd) : "Chọn phân loại"} feedback={feedback}>
    {!sku ? <Button className="w-full whitespace-normal" disabled>Chọn phân loại</Button>
      : !canAddSku(sku) ? <Button className="w-full" disabled>Tạm hết hàng</Button>
      : status === "anonymous" ? <Link aria-label="Đăng nhập để thêm vào giỏ" href={`/login?next=${encodeURIComponent(next)}`} className={buttonVariants({ variant: "clay", className: "w-full" })}><span className="lg:hidden">Đăng nhập để mua</span><span className="hidden lg:inline">Đăng nhập để thêm vào giỏ</span></Link>
      : <Button variant="clay" className="w-full" aria-label={command.isPending ? "Đang thêm…" : locked ? "Thử thêm lại" : "Thêm vào giỏ hàng"} disabled={command.isPending || status === "loading"} onClick={() => command.mutate(command.isError && isRetryable(command.error) && command.variables ? command.variables : { sku_id: sku.sku_id, delta: quantity })}>{command.isPending ? "Đang thêm…" : locked ? "Thử thêm lại" : <><span className="lg:hidden">Thêm vào giỏ</span><span className="hidden lg:inline">Thêm vào giỏ hàng</span></>}</Button>}
  </MobileActionDock>;
}
