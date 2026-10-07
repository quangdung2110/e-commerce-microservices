// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

import { httpError, problem } from "@/lib/api/__tests__/helpers";

const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn() }));
vi.mock("next/navigation", () => ({
  useRouter: () => router,
  usePathname: () => "/admin/promotions/1/vouchers/new",
  useSearchParams: () => new URLSearchParams(),
}));
vi.mock("@/lib/api/promotion", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/api/promotion")>()),
  promotionApi: { getCampaign: vi.fn(), createPromotion: vi.fn() },
}));
vi.mock("@/lib/api/product", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/api/product")>()),
  productApi: { listCategories: vi.fn(), getProduct: vi.fn() },
}));

import { RuleBuilder } from "@/components/admin/promotions/rule-builder";
import { VoucherCreate } from "@/components/admin/promotions/voucher-form";
import { ToastProvider } from "@/components/ui/toast";
import { productApi, type Category } from "@/lib/api/product";
import { promotionApi, type Campaign } from "@/lib/api/promotion";
import { ALWAYS_TRUE_RULE, fromWire, newCondition, type DraftNode } from "@/lib/promotion/rule";

const campaign: Campaign = {
  id: "1",
  code: "SALE",
  name: "Sale tháng 10",
  description: null,
  publication_status: "DRAFT",
  phase: "DRAFT",
  start_time: "2026-10-01T00:00:00Z",
  end_time: "2026-12-31T00:00:00Z",
  revision: 3,
  created_by: "admin",
  updated_by: "admin",
  created_at: "2026-10-01T00:00:00Z",
  updated_at: "2026-10-01T00:00:00Z",
};

const phones = { category_id: "cat-phones", name: "Điện thoại", parent_id: null, attributes: [] } as unknown as Category;

function renderWithProviders(ui: React.ReactElement) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <ToastProvider>{ui}</ToastProvider>
    </QueryClientProvider>
  );
}

beforeEach(() => {
  vi.mocked(promotionApi.getCampaign).mockResolvedValue({ data: campaign, etag: '"3"' });
  vi.mocked(productApi.listCategories).mockResolvedValue([phones]);
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe("RuleBuilder", () => {
  it("bật “mọi đơn hàng” thì không hiện điều kiện; tắt đi thì thêm điều kiện danh mục", () => {
    const onChange = vi.fn();
    const { rerender } = render(
      <RuleBuilder value={fromWire(ALWAYS_TRUE_RULE)} onChange={onChange} categories={[phones]} errors={[]} />
    );
    expect(screen.queryByText("Điều kiện 1")).toBeNull();
    expect(screen.getByText(/Tóm tắt: Mọi đơn hàng/)).toBeTruthy();

    fireEvent.click(screen.getByRole("switch"));
    const next = onChange.mock.calls[0][0] as DraftNode;
    expect(next).toMatchObject({
      node_type: "LOGICAL",
      operator: "AND",
      children: [{ node_type: "CONDITION", field: "cart.items.category_id", operator: "IN", value: [] }],
    });

    rerender(<RuleBuilder value={next} onChange={onChange} categories={[phones]} errors={["Điều kiện › 1: hãy chọn ít nhất một danh mục."]} />);
    expect(screen.getByText("Điều kiện 1")).toBeTruthy();
    expect(screen.getByRole("alert").textContent).toContain("hãy chọn ít nhất một danh mục");
  });

  it("chọn danh mục và thêm điều kiện mới trả về cây AST đúng", () => {
    const onChange = vi.fn();
    const value: DraftNode = {
      node_type: "LOGICAL",
      operator: "AND",
      children: [newCondition("cart.items.category_id")],
    };
    renderWithProviders(<RuleBuilder value={value} onChange={onChange} categories={[phones]} errors={[]} />);

    fireEvent.click(screen.getByRole("checkbox", { name: /Điện thoại/ }));
    expect(onChange.mock.calls[0][0]).toMatchObject({
      children: [{ field: "cart.items.category_id", value: ["cat-phones"] }],
    });

    fireEvent.click(screen.getByRole("button", { name: /Thêm điều kiện/ }));
    const added = onChange.mock.calls[1][0] as { children: unknown[] };
    expect(added.children).toHaveLength(2);
    expect(added.children[1]).toMatchObject({ field: "cart.total_amount", operator: "GREATER_THAN_OR_EQUAL", value: null });
  });
});

describe("tạo voucher", () => {
  async function fillRequired() {
    await screen.findByLabelText(/Mã voucher/);
    fireEvent.change(screen.getByLabelText(/Mã voucher/), { target: { value: "giam10" } });
    fireEvent.change(screen.getByLabelText(/Tên voucher/), { target: { value: "Giảm 10%" } });
    fireEvent.change(screen.getByLabelText(/Phần trăm giảm/), { target: { value: "10" } });
  }

  it("gửi payload đúng contract kèm If-Match của campaign cha", async () => {
    vi.mocked(promotionApi.createPromotion).mockResolvedValue({ data: { id: "7" } as never, etag: '"4"' });
    renderWithProviders(<VoucherCreate campaignId="1" />);
    await fillRequired();

    fireEvent.click(screen.getByRole("button", { name: "Thêm voucher" }));
    await waitFor(() => expect(promotionApi.createPromotion).toHaveBeenCalledTimes(1));

    const [campaignId, etag, body] = vi.mocked(promotionApi.createPromotion).mock.calls[0];
    expect(campaignId).toBe("1");
    expect(etag).toBe('"3"');
    expect(body).toMatchObject({
      code: "GIAM10",
      name: "Giảm 10%",
      distribution_mode: "PUBLIC_SAVE",
      discount: { type: "PERCENTAGE", value_basis_points: 1000, max_discount: null },
      min_order_amount: { amount_minor: 0, currency: "VND" },
      conditions_rule: ALWAYS_TRUE_RULE,
      rule_schema_version: 1,
      validity: { mode: "CAMPAIGN_END" },
      budget: { total_claim_quantity: null, total_redemption_quantity: null, total_budget: null },
    });
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith("/admin/promotions/1/vouchers/7"));
  });

  it("không gửi khi thiếu mã, tên hoặc mức giảm", async () => {
    renderWithProviders(<VoucherCreate campaignId="1" />);
    await screen.findByLabelText(/Mã voucher/);

    fireEvent.click(screen.getByRole("button", { name: "Thêm voucher" }));
    expect(await screen.findByText("Hãy nhập mã.")).toBeTruthy();
    expect(screen.getByText("Hãy nhập tên voucher.")).toBeTruthy();
    expect(promotionApi.createPromotion).not.toHaveBeenCalled();
  });

  it("412 báo campaign đã đổi, giữ nguyên dữ liệu đang nhập và không điều hướng", async () => {
    vi.mocked(promotionApi.createPromotion).mockRejectedValue(
      httpError(412, problem("CAMPAIGN_REVISION_MISMATCH", 412))
    );
    renderWithProviders(<VoucherCreate campaignId="1" />);
    await fillRequired();

    fireEvent.click(screen.getByRole("button", { name: "Thêm voucher" }));
    expect(await screen.findByText("Campaign vừa được người khác thay đổi")).toBeTruthy();
    expect(screen.getByRole("button", { name: "Tải lại dữ liệu" })).toBeTruthy();
    expect((screen.getByLabelText(/Tên voucher/) as HTMLInputElement).value).toBe("Giảm 10%");
    expect(router.replace).not.toHaveBeenCalled();
  });

  it("campaign đã xuất bản thì không cho thêm voucher", async () => {
    vi.mocked(promotionApi.getCampaign).mockResolvedValue({
      data: { ...campaign, publication_status: "PUBLISHED", phase: "ACTIVE" },
      etag: '"4"',
    });
    renderWithProviders(<VoucherCreate campaignId="1" />);
    expect(await screen.findByText("Campaign không còn là bản nháp")).toBeTruthy();
    expect(screen.queryByLabelText(/Mã voucher/)).toBeNull();
  });
});
