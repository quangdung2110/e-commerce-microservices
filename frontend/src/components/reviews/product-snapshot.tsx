import { Package } from "lucide-react";

import { StorefrontImage } from "@/components/catalog/storefront-image";
import { cn } from "@/lib/utils";

interface ProductSnapshotProps {
  name: string | null;
  imageUrl: string | null;
  variantName: string | null;
  size?: "sm" | "md";
  className?: string;
}

/**
 * Sản phẩm đang được đánh giá, theo snapshot lúc mua của Review (ADR-012). Entitlement
 * cấp trước khi có snapshot không có tên/ảnh: hiện câu thay thế thay vì để trống.
 */
export function ProductSnapshot({ name, imageUrl, variantName, size = "md", className }: ProductSnapshotProps) {
  const box = size === "sm" ? "size-16" : "size-20";
  return (
    <div className={cn("flex min-w-0 items-center gap-4", className)}>
      <div className={cn("shrink-0 overflow-hidden rounded-xl bg-surface-2", box)}>
        {imageUrl ? (
          <StorefrontImage src={imageUrl} alt={name ?? "Sản phẩm đã mua"} className="size-full object-cover" />
        ) : (
          <div className="grid size-full place-content-center text-muted-foreground">
            <Package className="size-6" aria-hidden />
            <span className="sr-only">Ảnh sản phẩm chưa khả dụng</span>
          </div>
        )}
      </div>
      <div className="min-w-0 space-y-1">
        <p className="line-clamp-2 text-[15px] font-medium">{name ?? "Sản phẩm đã mua"}</p>
        {variantName ? <p className="text-caption text-muted-foreground">{variantName}</p> : null}
      </div>
    </div>
  );
}
