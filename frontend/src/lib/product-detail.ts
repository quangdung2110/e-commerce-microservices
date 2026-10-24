import type { AttributeScalar, DetailSku, ProductDetail } from "@/lib/api/product-detail";

/** Quantity in a login return URL is an intent, bounded by the Cart contract. */
export function pdpQuantity(value?: string | number): number {
  if (typeof value === "string" && !/^[1-9]\d{0,2}$/.test(value)) return 1;
  const quantity = Number(value);
  return Number.isInteger(quantity) && quantity >= 1 && quantity <= 100 ? quantity : 1;
}

export function scalarLabel(value: AttributeScalar): string {
  return typeof value === "boolean" ? value ? "Có" : "Không" : String(value);
}

export function skuLabel(product: ProductDetail, sku: DetailSku): string {
  return product.variant_axes.map((axis) => {
    const value = sku.variant_attributes[axis.code];
    const option = axis.options.find((option) => option.value === value);
    return `${axis.name}: ${option?.label ?? scalarLabel(value)}`;
  }).join(" · ") || sku.sku_code;
}

/** Cùng một SKU phải khớp tất cả trục; không ghép các option thành SKU giả. */
export function resolveSku(product: ProductDetail, selected: Record<string, AttributeScalar>): DetailSku | undefined {
  return product.skus.find((sku) => product.variant_axes.every((axis) => selected[axis.code] !== undefined && sku.variant_attributes[axis.code] === selected[axis.code]));
}

export function skuGallery(product: ProductDetail, sku?: DetailSku) {
  const media = sku ? product.media.filter((item) => item.sku_id === sku.sku_id) : [];
  return (media.length ? media : product.media.filter((item) => item.sku_id === null))
    .toSorted((a, b) => a.sort_order - b.sort_order);
}

export function canAddSku(sku?: DetailSku): boolean {
  return !!sku && sku.availability_status !== "OUT_OF_STOCK";
}
