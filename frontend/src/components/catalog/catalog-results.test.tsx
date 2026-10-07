// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import type { SearchPage } from "@/lib/api/search";
import { CatalogResults } from "./catalog-results";

const mock = vi.hoisted(() => ({ refresh: vi.fn(), fetchNextPage: vi.fn(), query: {} }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh: mock.refresh }) }));
vi.mock("@/hooks/use-cursor-list", () => ({ useCursorInfiniteList: () => mock.query }));
const page: SearchPage = {
  items: [{ product_id: "product-1", sku_id: "cheapest-black", product_slug: "phone", name: "Điện thoại",
    thumbnail_url: "/media/placeholder/product.svg", base_price_vnd: 1000000, selling_price_vnd: 1000000,
    discount_percentage: 0, product_rating_avg: null, product_rating_count: 0, is_in_stock: false }],
  total_products: 1, next_cursor: null,
  facets: { count_semantics: "DISTINCT_PRODUCT", count_accuracy: "APPROXIMATE", filters: [] },
};
afterEach(() => { cleanup(); vi.clearAllMocks(); });
describe("product result cards", () => {
  it("shows the product count and keeps the representative SKU link across loaded pages", () => {
    mock.query = { data: { pages: [page, page] }, isFetching: false };
    render(<CatalogResults params={{ in_stock: false }} initial={page} failure={null} instanceKey="test" />);
    expect(screen.getByText("1 sản phẩm phù hợp")).toBeTruthy();
    expect(screen.getAllByRole("link")).toHaveLength(1);
    expect(screen.getByRole("link").getAttribute("href")).toBe("/products/phone?sku=cheapest-black");
  });
  it("restarts search after snapshot expiry instead of retrying the expired cursor", () => {
    mock.query = { data: { pages: [page] }, isFetching: false, isFetchNextPageError: true,
      fetchNextPage: mock.fetchNextPage,
      error: { status: 400, problem: { status: 400, code: "INVALID_CURSOR" } } };
    render(<CatalogResults params={{ in_stock: false }} initial={page} failure={null} instanceKey="test" />);
    fireEvent.click(screen.getByRole("button", { name: "Thử lại" }));
    expect(mock.refresh).toHaveBeenCalledOnce();
    expect(mock.fetchNextPage).not.toHaveBeenCalled();
  });
});
