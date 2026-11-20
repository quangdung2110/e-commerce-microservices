// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { CatalogFilters } from "./catalog-filters";
import { FacetGroup } from "./facet-group";

const push = vi.hoisted(() => vi.fn());
vi.mock("next/navigation", () => ({ useRouter: () => ({ push }) }));
beforeEach(() => push.mockReset());
afterEach(cleanup);

describe("responsive catalog filters", () => {
  it("finds Vietnamese option labels without accents or case sensitivity", () => {
    render(<FacetGroup facet={{ code: "color", label: "Màu sắc", type: "MULTI_SELECT", options: ["Đen", "Trắng", "Bạc", "Đỏ", "Xanh", "Tím", "Hồng"].map(label => ({ value: label, label, count: 1 })) }} selected={new Set()} onChange={vi.fn()} initiallyOpen disabled={false} />);
    fireEvent.change(screen.getByRole("searchbox"), { target: { value: "den" } });
    expect(screen.getAllByRole("checkbox")).toHaveLength(1);
    expect(screen.getByRole("checkbox", { name: "Đen (≈1)" })).toBeTruthy();
  });
  it("preserves selections beyond the preview and in collapsed groups, and removes them explicitly", async () => {
    const options = Array.from({ length: 12 }, (_, index) => ({ value: `brand:s:${index}`, label: `Hãng ${index}`, count: 2 }));
    render(<CatalogFilters params={{ in_stock: false, filter: ["brand:s:11"] }} categories={[]} facets={{ count_semantics: "DISTINCT_PRODUCT", count_accuracy: "APPROXIMATE", filters: [{ code: "brand", label: "Thương hiệu", type: "MULTI_SELECT", options }] }} action="/search" />);
    fireEvent.click(screen.getByRole("button", { name: /Lọc và sắp xếp/ }));
    const dialog = within(await screen.findByRole("dialog"));
    const group = within(dialog.getByRole("group", { name: /Thương hiệu/ }));
    expect(group.getAllByRole("checkbox")).toHaveLength(7);
    expect((dialog.getByRole("checkbox", { name: /Hãng 11/ }) as HTMLInputElement).checked).toBe(true);
    fireEvent.change(dialog.getByRole("searchbox", { name: "Tìm trong Thương hiệu" }), { target: { value: "Hãng 9" } });
    expect(group.getAllByRole("checkbox")).toHaveLength(2);
    fireEvent.keyDown(dialog.getByRole("searchbox"), { key: "Enter" });
    expect(push).not.toHaveBeenCalled();
    fireEvent.click(dialog.getByRole("button", { name: /Thương hiệu.*Đã chọn 1/ }));
    fireEvent.click(dialog.getByRole("button", { name: "Áp dụng" }));
    expect(new URL(push.mock.calls[0][0], "http://localhost").searchParams.getAll("filter")).toEqual(["brand:s:11"]);
  });

  it("hides single-option groups until selected and lets an old URL filter be removed", async () => {
    render(<CatalogFilters params={{ in_stock: false, filter: ["legacy:s:old"] }} categories={[]} facets={{ count_semantics: "DISTINCT_PRODUCT", count_accuracy: "APPROXIMATE", filters: [{ code: "brand", label: "Thương hiệu", type: "MULTI_SELECT", options: [{ value: "brand:s:a", label: "A", count: 1 }] }] }} action="/search" />);
    fireEvent.click(screen.getByRole("button", { name: /Lọc và sắp xếp/ }));
    const dialog = within(await screen.findByRole("dialog"));
    expect(dialog.queryByRole("button", { name: "Thương hiệu" })).toBeNull();
    fireEvent.click(dialog.getByRole("button", { name: "Bỏ bộ lọc từ liên kết 1" }));
    fireEvent.click(dialog.getByRole("button", { name: "Áp dụng" }));
    expect(new URL(push.mock.calls[0][0], "http://localhost").searchParams.getAll("filter")).toEqual([]);
  });
  it("opens a labelled mobile dialog, applies URL filters and closes it", async () => {
    render(<CatalogFilters params={{ q: "tai nghe", in_stock: false, sort: "relevance", filter: ["brand:s:a+b / &"] }} categories={[]} facets={null} action="/search" />);
    fireEvent.click(screen.getByRole("button", { name: /Lọc và sắp xếp/ }));
    const dialog = within(await screen.findByRole("dialog", { name: "Lọc và sắp xếp" }));
    fireEvent.change(dialog.getByLabelText("Sắp xếp"), { target: { value: "price_asc" } });
    fireEvent.change(dialog.getByLabelText("Giá tối thiểu"), { target: { value: "0" } });
    fireEvent.click(dialog.getByRole("button", { name: "Áp dụng" }));
    expect(push).toHaveBeenCalledTimes(1);
    const query = new URL(push.mock.calls[0][0], "http://localhost").searchParams;
    expect(query.get("q")).toBe("tai nghe");
    expect(query.get("sort")).toBe("price_asc");
    expect(query.get("price_min")).toBe("0");
    expect(query.get("in_stock")).toBe("false");
    expect(query.getAll("filter")).toEqual(["brand:s:a+b / &"]);
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
  });

  it("keeps a reversed price range open for correction instead of navigating", async () => {
    render(<CatalogFilters params={{ in_stock: false }} categories={[]} facets={null} action="/search" />);
    fireEvent.click(screen.getByRole("button", { name: /Lọc và sắp xếp/ }));
    const dialog = within(await screen.findByRole("dialog"));
    fireEvent.change(dialog.getByLabelText("Giá tối thiểu"), { target: { value: "200" } });
    fireEvent.change(dialog.getByLabelText("Giá tối đa"), { target: { value: "100" } });
    fireEvent.submit(dialog.getByRole("form", { name: "Lọc sản phẩm" }));
    expect(dialog.getByRole("alert").textContent).toContain("Giá tối đa phải lớn hơn");
    expect(push).not.toHaveBeenCalled();
    expect(screen.getByRole("dialog")).toBeTruthy();
  });

  it("clears filters while keeping the keyword and fixed category route", () => {
    render(<CatalogFilters params={{ q: "a & b", category: "phones", in_stock: true, filter: ["a"], has_promotion: true }} categories={[]} facets={null} action="/c/phones" fixedCategory />);
    const clearLinks = screen.getAllByRole("link", { name: "Xóa bộ lọc" });
    for (const link of clearLinks) {
      const url = new URL(link.getAttribute("href")!, "http://localhost");
      expect(url.pathname).toBe("/c/phones");
      expect([...url.searchParams]).toEqual([["in_stock", "false"], ["q", "a & b"]]);
    }
    expect(screen.getByLabelText("3 bộ lọc đang áp dụng")).toBeTruthy();
  });

  it("gives each form unique label targets when the drawer is open", async () => {
    render(<CatalogFilters params={{ in_stock: false }} categories={[]} facets={null} action="/search" />);
    fireEvent.click(screen.getByRole("button", { name: /Lọc và sắp xếp/ }));
    await screen.findByRole("dialog");
    const ids = [...document.querySelectorAll("input[id], select[id]")].map((el) => el.id);
    expect(new Set(ids).size).toBe(ids.length);
    for (const label of document.querySelectorAll("label[for]")) {
      expect(document.getElementById(label.getAttribute("for")!)).toBeTruthy();
    }
  });
});
