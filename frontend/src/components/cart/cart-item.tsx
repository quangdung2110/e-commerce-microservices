"use client";

import { Minus, Plus, Trash2 } from "lucide-react";
import { StorefrontImage } from "@/components/catalog/storefront-image";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Skeleton } from "@/components/ui/skeleton";
import type { Cart, CartItem } from "@/lib/api/cart";
import { currentLineTotal, formatCartMoney, validCartMoney } from "@/lib/cart";

export function CartItemSkeleton() {
  return <div className="flex gap-4 rounded-xl border border-border bg-surface-overlay p-6">
    <Skeleton className="size-20 shrink-0 rounded-lg" />
    <div className="min-w-0 flex-1 space-y-3"><Skeleton className="h-5 w-3/4" /><Skeleton className="h-4 w-1/2" /><Skeleton className="h-11 w-36" /></div>
  </div>;
}

interface CartItemCardProps {
  item: CartItem;
  freshness: Cart["catalog_freshness"];
  selected: boolean;
  disabled: boolean;
  onSelect: (selected: boolean) => void;
  onAdjust: (delta: number) => void;
  onRemove: () => void;
}

export function CartItemCard({ item, freshness, selected, disabled, onSelect, onAdjust, onRemove }: CartItemCardProps) {
  const current = freshness === "CURRENT" && validCartMoney(item.current_price) ? item.current_price : null;
  const line = currentLineTotal(item, freshness);
  const unavailable = freshness === "CURRENT" && !item.is_available;
  return <article className="space-y-4 rounded-xl border border-border bg-surface-overlay p-6" aria-label={item.product_name} aria-busy={disabled}>
    <div className="flex items-start gap-3 sm:gap-4">
      <label className="flex min-h-11 min-w-11 shrink-0 items-center justify-center">
        <Checkbox checked={selected} disabled={disabled} onCheckedChange={onSelect} aria-label={`Chọn ${item.product_name}`} />
      </label>
      <div className="size-16 shrink-0 overflow-hidden rounded-lg bg-surface-2 sm:size-20"><StorefrontImage src={item.image_url ?? ""} alt={item.product_name} className="size-full object-cover" /></div>
      <div className="min-w-0 flex-1 space-y-2">
        <h3 className="break-words font-sans text-[15px] font-medium">{item.product_name}</h3>
        {item.variant_attributes && <p className="break-words text-caption text-muted-foreground">{Object.entries(item.variant_attributes).map(([name, value]) => `${name}: ${value}`).join(" · ")}</p>}
        {unavailable && <Badge variant="destructive">Ngưng bán</Badge>}
        {freshness === "DEGRADED" && <Badge variant="warning">Chưa xác minh tình trạng bán</Badge>}
        <p className="text-[15px] tabular-nums">{current ? formatCartMoney(current) : "Chưa xác minh giá"}</p>
        <p className="text-caption text-muted-foreground">Giá lúc thêm: {formatCartMoney(item.unit_price_at_add)}</p>
      </div>
    </div>
    <div className="flex flex-wrap items-center justify-between gap-3">
      <div className="flex items-center gap-2">
        <div className="flex items-center rounded-lg border border-border-strong">
          <Button variant="ghost" size="icon" disabled={disabled || item.quantity <= 1} aria-label={`Giảm số lượng ${item.product_name}`} onClick={() => onAdjust(-1)}><Minus aria-hidden /></Button>
          <span className="min-w-8 text-center tabular-nums" aria-label={`Số lượng ${item.product_name}: ${item.quantity}`}>{item.quantity}</span>
          <Button variant="ghost" size="icon" disabled={disabled || unavailable || item.quantity >= 100} aria-label={`Tăng số lượng ${item.product_name}`} onClick={() => onAdjust(1)}><Plus aria-hidden /></Button>
        </div>
        <Button variant="ghost" size="icon" disabled={disabled} aria-label={`Xóa ${item.product_name} khỏi giỏ`} onClick={onRemove}><Trash2 aria-hidden /></Button>
      </div>
      <p className="text-[15px] font-medium tabular-nums">{line ? formatCartMoney(line) : "Chưa tính được thành tiền"}</p>
    </div>
  </article>;
}
