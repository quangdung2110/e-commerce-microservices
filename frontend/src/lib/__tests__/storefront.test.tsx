import { describe, expect, it } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import { searchQueryString, parseSearchParams, searchPriceErrors, flattenCategories, categoryPath } from "@/lib/storefront-search";
import { canAddSku, resolveSku, skuGallery, scalarLabel } from "@/lib/product-detail";
import type { ProductDetail } from "@/lib/api/product-detail";
import { ProductCard } from "@/components/catalog/product-card";

const product: ProductDetail = {
  product_id: "p", slug: "canonical-slug", name: "Điện thoại", description: null, catalog_revision: 1,
  category: { category_id: "c", name: "Danh mục", slug: "category", path: [{ category_id: "c", name: "Danh mục", slug: "category" }] },
  attributes: [],
  variant_axes: [
    { code: "color", name: "Màu", value_type: "SELECT", options: [{ value: "black", label: "Đen" }, { value: "white", label: "Trắng" }] },
    { code: "capacity", name: "Dung lượng", value_type: "NUMBER", options: [{ value: 128, label: null }, { value: 256, label: null }] },
  ],
  skus: [
    { sku_id: "s1", sku_code: "BLACK128", variant_attributes: { color: "black", capacity: 128 }, thumbnail_url: "/media/1", base_price_vnd: 100, selling_price_vnd: 80, availability_status: "UNKNOWN" },
    { sku_id: "s2", sku_code: "WHITE256", variant_attributes: { color: "white", capacity: 256 }, thumbnail_url: "/media/2", base_price_vnd: 100, selling_price_vnd: 100, availability_status: "OUT_OF_STOCK" },
  ],
  media: [
    { media_id: "m1", sku_id: null, media_type: "IMAGE", url: "https://cdn/product", alt_text: null, sort_order: 0 },
    { media_id: "m2", sku_id: "s1", media_type: "IMAGE", url: "https://cdn/sku", alt_text: null, sort_order: 0 },
  ],
  review_section: { status: "UNAVAILABLE", summary: null, preview: [] },
};
describe("Search v2", () => {
  it("round-trips opaque facet tokens and false without bracket syntax", () => {
    const token = "brand:s:a+b / &";
    const query = new URLSearchParams(searchQueryString({ filter: [token, "color:s:black"], in_stock: false, price_min: 0 }));
    expect(query.getAll("filter")).toEqual([token, "color:s:black"]);
    expect(query.get("in_stock")).toBe("false");
    expect(query.get("price_min")).toBe("0");
    expect([...query.keys()]).not.toContain("filters[brand]");
  });
  it("drops URL cursors and fixes category to the route on a category page", () => {
    expect(parseSearchParams({ cursor: "old", category: "other", sort: "rating", filter: ["a", "b"], in_stock: "false" }, "phones"))
      .toMatchObject({ category: "phones", sort: "rating", filter: ["a", "b"], in_stock: false });
    expect(parseSearchParams({ cursor: "old" })).not.toHaveProperty("cursor");
  });
  it("defaults stock/sort and distinguishes invalid prices from absent prices", () => {
    const parsed = parseSearchParams({ sort: "rating_desc", price_min: "-1", price_max: "9007199254740992" });
    expect(parsed).toMatchObject({ sort: "relevance", in_stock: true, price_min: undefined, price_max: undefined });
    expect(searchPriceErrors({ price_min: "-1", price_max: "9007199254740992" })).toHaveLength(2);
    expect(searchPriceErrors({})).toEqual([]);
    expect(searchPriceErrors({ price_min: "0", price_max: "150000" })).toEqual([]);
  });
  it.each(["abc", "1.5", "1e3", "-1", "9007199254740992"])("reports invalid URL price %s", (price_min) => {
    expect(searchPriceErrors({ price_min })[0]).toContain("Giá tối thiểu");
    expect(searchPriceErrors({ price_max: price_min })[0]).toContain("Giá tối đa");
  });
  it("rejects a reversed range coming from the URL", () => {
    expect(searchPriceErrors({ price_min: "200", price_max: "100" })[0]).toContain("Giá tối đa");
  });
  it("walks the recursive children contract", () => {
    const leaf = { category_id: "c2", slug: "leaf", name: "Con", children: [], product_count: 0, product_count_updated_at: null };
    const parent = { ...leaf, category_id: "c1", slug: "root", children: [leaf] };
    expect(flattenCategories([parent]).map((node) => [node.slug, node.depth])).toEqual([["root", 0], ["leaf", 1]]);
    expect(categoryPath([parent], "c2").map(node => node.slug)).toEqual(["root", "leaf"]);
  });
  it("links the product card to the cheapest eligible representative SKU", () => {
    const html = renderToStaticMarkup(<ProductCard product={{ sku_id: "s1", product_id: "p", product_slug: "canonical-slug", name: "Tên khác hoàn toàn", thumbnail_url: "/media/a", base_price_vnd: 100, selling_price_vnd: 80, discount_percentage: 20, product_rating_avg: null, product_rating_count: 0, is_in_stock: false }} />);
    expect(html).toContain('/products/canonical-slug?sku=s1');
    expect(html).toContain("Từ ");
    expect(html).toContain("Chưa có đánh giá");
    expect(html).toContain("Chưa xác nhận còn hàng");
  });
});
describe("PDP contract semantics", () => {
  it("requires all axes to resolve the same real SKU, preserving scalar types", () => {
    expect(resolveSku(product, { color: "black", capacity: 128 })?.sku_id).toBe("s1");
    expect(resolveSku(product, { color: "black", capacity: 256 })).toBeUndefined();
    expect(resolveSku(product, { color: "black" })).toBeUndefined();
    expect(resolveSku(product, { color: "black", capacity: "128" })).toBeUndefined();
  });
  it("allows UNKNOWN and only disables OUT_OF_STOCK or no SKU", () => {
    expect(canAddSku(product.skus[0])).toBe(true);
    expect(canAddSku(product.skus[1])).toBe(false);
    expect(canAddSku(undefined)).toBe(false);
    expect(canAddSku({ ...product.skus[0], availability_status: "IN_STOCK" })).toBe(true);
  });
  it("prefers SKU media and falls back to product gallery", () => {
    expect(skuGallery(product, product.skus[0]).map((media) => media.media_id)).toEqual(["m2"]);
    expect(skuGallery(product, product.skus[1]).map((media) => media.media_id)).toEqual(["m1"]);
  });
  it("uses Vietnamese boolean labels", () => {
    expect(scalarLabel(true)).toBe("Có");
    expect(scalarLabel(false)).toBe("Không");
  });
});
