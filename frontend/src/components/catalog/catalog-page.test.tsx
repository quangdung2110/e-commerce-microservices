import { beforeEach, describe, expect, it, vi } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import type { CategoryNode } from "@/lib/api/search";
import { CatalogPage } from "./catalog-page";

const api = vi.hoisted(() => ({ categories: vi.fn(), search: vi.fn(), facets: vi.fn() }));
vi.mock("@/lib/api/storefront-server", () => ({ storefrontServer: api, publicFailure: () => ({ status: 503, problem: null }) }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn() }), notFound: () => { throw new Error("404"); } }));
vi.mock("./catalog-results", () => ({ CatalogResults: () => <div>Search results rendered</div> }));
vi.mock("./public-error", () => ({ PublicError: () => <div>Upstream error</div> }));
const node = (id: string, name: string, children: CategoryNode[] = []): CategoryNode => ({ category_id: id, name, slug: id, children, product_count: 0, product_count_updated_at: null });
beforeEach(() => {
  vi.clearAllMocks();
  api.categories.mockResolvedValue({ categories: [] });
  api.search.mockResolvedValue({ items: [], total_products: 0, next_cursor: null, facets: null });
  api.facets.mockResolvedValue({ filters: [] });
});
describe("catalog route validation and ancestry", () => {
  it.each(["-1", "abc", "1.5", "9007199254740992"])("shows a validation error for URL price %s without querying a broader result set", async (price_min) => {
    const html = renderToStaticMarkup(await CatalogPage({ raw: { price_min, in_stock: "false" } }));
    expect(api.search).not.toHaveBeenCalled();
    expect(html).toContain('role="alert"');
    expect(html).toContain("Khoảng giá không hợp lệ");
    expect(html).not.toContain("Search results rendered");
    if (price_min === "-1") expect(html).toContain('value="-1"');
  });
  it("rejects min > max from a deep link and accepts a corrected range", async () => {
    const html = renderToStaticMarkup(await CatalogPage({ raw: { price_min: "200", price_max: "100" } }));
    expect(api.search).not.toHaveBeenCalled();
    expect(html).toContain("Giá tối đa phải lớn hơn");
    await CatalogPage({ raw: { price_min: "0", price_max: "100" } });
    expect(api.search).toHaveBeenCalledWith(expect.objectContaining({ price_min: 0, price_max: 100 }));
  });
  it("renders three levels with links for ancestors and the current category last", async () => {
    api.categories.mockResolvedValue({ categories: [node("root", "Gốc", [node("middle", "Giữa", [node("leaf", "Lá")])])] });
    const html = renderToStaticMarkup(await CatalogPage({ raw: {}, slug: "leaf" }));
    const breadcrumb = html.slice(html.indexOf('aria-label="Đường dẫn"'), html.indexOf("</nav>"));
    expect(breadcrumb).toContain('href="/c/root"');
    expect(breadcrumb).toContain('href="/c/middle"');
    expect(breadcrumb).toContain('aria-current="page">Lá');
    expect(breadcrumb.indexOf("Gốc")).toBeLessThan(breadcrumb.indexOf("Giữa"));
    expect(breadcrumb.indexOf("Giữa")).toBeLessThan(breadcrumb.indexOf("Lá"));
  });
  it("follows renamed/moved categories from the latest tree", async () => {
    api.categories.mockResolvedValue({ categories: [node("old-root", "Cha cũ"), node("new-root", "Cha mới", [node("renamed-leaf", "Tên mới")])] });
    const html = renderToStaticMarkup(await CatalogPage({ raw: {}, slug: "renamed-leaf" }));
    const breadcrumb = html.slice(html.indexOf('aria-label="Đường dẫn"'), html.indexOf("</nav>"));
    expect(breadcrumb).toContain("Cha mới");
    expect(breadcrumb).toContain("Tên mới");
    expect(breadcrumb).not.toContain("Cha cũ");
    await expect(CatalogPage({ raw: {}, slug: "old-slug" })).rejects.toThrow("404");
  });
});
