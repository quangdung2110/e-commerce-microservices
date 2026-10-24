// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { VoucherWalletContent } from "@/components/vouchers/voucher-wallet-content";
import { promotionApi, type WalletEntry } from "@/lib/api/promotion";
import { useAuthStore } from "@/store/useAuthStore";
import { httpError, problem } from "@/lib/api/__tests__/helpers";

const router = vi.hoisted(() => ({ replace: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => router }));
vi.mock("@/lib/api/promotion", () => ({ promotionApi: { listMyWallet: vi.fn() } }));

const entry = (id: string, status: WalletEntry["effective_status"] = "SAVED"): WalletEntry => ({
  wallet: { wallet_id: id, user_id: "buyer-a", promotion_id: id, status,
    saved_at: "2026-10-20T00:00:00Z", expired_at: "2026-11-20T00:00:00Z", updated_at: "2026-10-20T00:00:00Z",
    used_order_id: status === "USED" ? "770060" : null, used_at: status === "USED" ? "2026-10-20T01:00:00Z" : null,
    discount_amount: status === "USED" ? { amount_minor: 20000, currency: "VND" } : null },
  effective_status: status, code: `VOUCHER-${id}`, discount: { type: "PERCENTAGE", value_basis_points: 1250, max_discount: { amount_minor: 50000, currency: "VND" } },
  min_order_amount: { amount_minor: 100000, currency: "VND" }, conditions_summary: "Thanh toán bằng Stripe", is_enabled: true,
});
const page = (items: WalletEntry[], cursor: string | null = null) => ({ items, next_cursor: cursor, as_of: "2026-10-20T00:00:00Z" });
function mount(props: Parameters<typeof VoucherWalletContent>[0] = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const view = render(<QueryClientProvider client={client}><VoucherWalletContent {...props} /></QueryClientProvider>);
  return { client, ...view };
}

beforeEach(() => {
  vi.clearAllMocks();
  useAuthStore.getState().setSession(`e30.${btoa(JSON.stringify({ sub: "buyer-a", roles: ["USER"] }))}.test`);
});
afterEach(cleanup);

describe("independent voucher wallet", () => {
  it("reads without cart/context, appends cursor pages and shows real usage details", async () => {
    vi.mocked(promotionApi.listMyWallet).mockResolvedValueOnce(page([entry("1")], "next"))
      .mockResolvedValueOnce(page([entry("2", "USED")]));
    mount();
    await screen.findByText("VOUCHER-1");
    expect(promotionApi.listMyWallet).toHaveBeenNthCalledWith(1, { status: undefined, cursor: undefined, limit: 20 }, expect.any(AbortSignal));
    fireEvent.click(screen.getByRole("button", { name: "Tải thêm voucher" }));
    await screen.findByText("VOUCHER-2");
    expect(screen.getByText("VOUCHER-1")).toBeTruthy();
    expect(promotionApi.listMyWallet).toHaveBeenNthCalledWith(2, { status: undefined, cursor: "next", limit: 20 }, expect.any(AbortSignal));
    expect(screen.getByRole("link", { name: "Xem đơn #770060" }).getAttribute("href")).toBe("/orders/770060");
    expect(screen.getByText(/Đã giảm 20\.000/)).toBeTruthy();
    expect(screen.getAllByText(/Giảm 12,5%/).length).toBe(2);
    expect(screen.queryByRole("button", { name: /Áp dụng/ })).toBeNull();
  });

  it("uses backend effective status and never marks USED expired on the client", async () => {
    const expired = entry("expired", "EXPIRED"); expired.wallet.status = "SAVED";
    vi.mocked(promotionApi.listMyWallet).mockResolvedValue(page([expired, entry("used", "USED")]));
    mount({ status: "EXPIRED" });
    await screen.findByText("VOUCHER-expired");
    expect(screen.getByText("Hết hạn", { selector: "span" })).toBeTruthy();
    expect(screen.getByText("Đã dùng", { selector: "span" })).toBeTruthy();
    expect(promotionApi.listMyWallet).toHaveBeenCalledWith(expect.objectContaining({ status: "EXPIRED" }), expect.any(AbortSignal));
  });

  it("changes filter URL and does not query invalid URLs", async () => {
    vi.mocked(promotionApi.listMyWallet).mockResolvedValue(page([]));
    const view = mount({ invalidFilter: true });
    expect(screen.getByText(/Bộ lọc ví voucher không hợp lệ/)).toBeTruthy();
    expect(promotionApi.listMyWallet).not.toHaveBeenCalled();
    view.unmount(); mount();
    await screen.findByText("Ví voucher của bạn đang trống");
    fireEvent.click(screen.getByRole("tab", { name: "Đã dùng" }));
    expect(router.replace).toHaveBeenCalledWith("/vouchers?status=USED", { scroll: false });
  });

  it("shows initial error and retries instead of presenting an empty wallet", async () => {
    vi.mocked(promotionApi.listMyWallet).mockRejectedValueOnce(httpError(503, problem("DEPENDENCY_UNAVAILABLE", 503)))
      .mockResolvedValueOnce(page([]));
    mount();
    await screen.findByRole("button", { name: "Thử lại" });
    expect(screen.queryByText("Ví voucher của bạn đang trống")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Thử lại" }));
    await screen.findByText("Ví voucher của bạn đang trống");
  });

  it("isolates cached wallet lists between accounts", async () => {
    vi.mocked(promotionApi.listMyWallet).mockImplementation(async () => page([entry(useAuthStore.getState().claims?.sub ?? "none")]));
    mount(); await screen.findByText("VOUCHER-buyer-a");
    act(() => useAuthStore.getState().setSession(`e30.${btoa(JSON.stringify({ sub: "buyer-b", roles: ["USER"] }))}.test`));
    await screen.findByText("VOUCHER-buyer-b");
    await waitFor(() => expect(screen.queryByText("VOUCHER-buyer-a")).toBeNull());
  });
});
