"use client";
import Link from "next/link";
import { useQueryClient } from "@tanstack/react-query";
import { Button, buttonVariants } from "@/components/ui/button";
import { ErrorState } from "@/components/ui/error-state";
import { useCommand } from "@/hooks/use-command";
import { cartApi } from "@/lib/api/cart";
import { cartKeys } from "@/hooks/use-cart";
import { canAddSku } from "@/lib/product-detail";
import { isRetryable } from "@/lib/api/problem";
import type { DetailSku } from "@/lib/api/product-detail";
import { useAuthStore } from "@/store/useAuthStore";
import { useToast } from "@/components/ui/toast";
import { useEffect } from "react";

export function AddToCartButton({ sku, quantity, slug, onLockChange }: { sku?: DetailSku; quantity: number; slug: string; onLockChange: (locked: boolean) => void }) {
  const status = useAuthStore((state) => state.status);
  const client = useQueryClient();
  const { addToast } = useToast();
  const command = useCommand({
    mutationFn: (body: { sku_id: string; delta: number }, { commandId }) => cartApi.adjust(body, commandId),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: cartKeys.all });
      addToast({ type: "success", title: "Đã thêm vào giỏ hàng", message: `${quantity} sản phẩm` });
    },
  });
  const locked = command.isPending || (command.isError && isRetryable(command.error));
  useEffect(() => { onLockChange(locked); }, [locked, onLockChange]);
  const next = `/products/${encodeURIComponent(slug)}${sku ? `?sku=${encodeURIComponent(sku.sku_id)}` : ""}`;
  if (!sku) return <Button className="w-full" disabled>Chọn một biến thể hợp lệ</Button>;
  if (!canAddSku(sku)) return <Button className="w-full" disabled>Tạm hết hàng</Button>;
  if (status === "anonymous") return <Link href={`/login?next=${encodeURIComponent(next)}`} className={buttonVariants({ variant: "clay", className: "w-full" })}>Đăng nhập để thêm vào giỏ</Link>;
  return <div className="space-y-4">
    <Button variant="clay" size="lg" className="w-full" disabled={command.isPending || status === "loading"} onClick={() => command.mutate(command.isError && isRetryable(command.error) && command.variables ? command.variables : { sku_id: sku.sku_id, delta: quantity })}>{command.isPending ? "Đang thêm…" : locked ? "Thử thêm lại" : "Thêm vào giỏ hàng"}</Button>
    {command.isError && <ErrorState error={command.error} />}
    {locked && !command.isPending && <p className="text-caption text-muted-foreground">Kết quả lần gửi trước chưa rõ. Thử lại cùng lựa chọn để tránh thêm hai lần.</p>}
  </div>;
}
