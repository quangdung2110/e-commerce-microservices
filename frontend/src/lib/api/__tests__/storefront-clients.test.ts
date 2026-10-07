import { beforeEach, describe, expect, it, vi } from "vitest";
vi.mock("@/lib/api/client", () => ({ api: { get: vi.fn(), post: vi.fn(), delete: vi.fn() } }));
import { api } from "@/lib/api/client";
import { searchApi } from "@/lib/api/search";
import { reviewApi } from "@/lib/api/review";
import { cartApi } from "@/lib/api/cart";
beforeEach(() => { vi.clearAllMocks(); vi.mocked(api.get).mockResolvedValue({ data: {} }); vi.mocked(api.post).mockResolvedValue({ data: {} }); vi.mocked(api.delete).mockResolvedValue({ data: { deleted_count: 2 } }); });
describe("storefront HTTP contracts", () => {
  it("removes a batch with a JSON DELETE body and the caller's key", async () => {
    expect(await cartApi.remove({ sku_ids: ["s1", "s2"] }, "intent-1234567890")).toEqual({ deleted_count: 2 });
    expect(api.delete).toHaveBeenCalledWith("/api/v1/carts/mine/items", { data: { sku_ids: ["s1", "s2"] }, headers: { "Idempotency-Key": "intent-1234567890" } });
  });
  it("adds with delta at mine/items and keeps the provided idempotency key", async () => {
    await cartApi.adjust({ sku_id: "s", delta: 2 }, "intent-1234567890");
    await cartApi.adjust({ sku_id: "s", delta: 2 }, "intent-1234567890");
    expect(api.post).toHaveBeenNthCalledWith(1, "/api/v1/carts/mine/items", { sku_id: "s", delta: 2 }, { headers: { "Idempotency-Key": "intent-1234567890" } });
    expect(vi.mocked(api.post).mock.calls[0]).toEqual(vi.mocked(api.post).mock.calls[1]);
  });
  it("uses suggestions items with the v2 endpoint", async () => {
    await searchApi.suggestions("điện thoại");
    expect(api.get).toHaveBeenCalledWith("/api/v2/search/suggestions", expect.objectContaining({ params: { q: "điện thoại", limit: 8 } }));
  });
  it("loads public reviews with has_media only when true", async () => {
    await reviewApi.list("product", { rating: 5, has_media: true, cursor: "next", limit: 20 });
    expect(api.get).toHaveBeenCalledWith("/api/v1/reviews/products/product", expect.objectContaining({ params: { rating: 5, has_media: true, cursor: "next", limit: 20 } }));
  });
});
