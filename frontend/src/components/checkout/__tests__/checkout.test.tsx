// @vitest-environment jsdom
import { StrictMode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { CheckoutPageContent } from "@/components/checkout/checkout-page-content";
import { checkoutApi, type CheckoutPreview, type SubmissionReady, type VoucherOption } from "@/lib/api/checkout";
import { promotionApi } from "@/lib/api/promotion";
import { userApi, type Address } from "@/lib/api/user";
import { httpError, networkError, problem } from "@/lib/api/__tests__/helpers";
import { useAuthStore } from "@/store/useAuthStore";

const { replace } = vi.hoisted(() => ({ replace: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ replace }) }));
vi.mock("@/lib/api/checkout", () => ({ checkoutApi: { preview: vi.fn(), submit: vi.fn(), submission: vi.fn(), voucherOptions: vi.fn() } }));
vi.mock("@/lib/api/promotion", () => ({ promotionApi: { saveVoucher: vi.fn(), claimVoucher: vi.fn() } }));
vi.mock("@/lib/api/user", () => ({ userApi: { listAddresses: vi.fn() } }));
const token = "01234567-1234-4234-8234-0123456789ab";
const secondToken = "11234567-1234-4234-8234-0123456789ab";
const address: Address = {
  id: "21234567-1234-4234-8234-0123456789ab", recipient_name: "Nguyễn An", recipient_phone: "+84912345678",
  address_line: "12 Nguyễn Huệ", province_code: "79", district_code: "760", ward_code: "26734", is_default: true,
  created_at: "2026-10-07T00:00:00Z", updated_at: "2026-10-07T00:00:00Z",
};
const money = (amount_minor: number) => ({ amount_minor, currency: "VND" as const });
const preview: CheckoutPreview = {
  checkout_token: token, expires_at: "2099-10-07T00:20:00Z",
  address: { address_id: address.id, recipient_name: address.recipient_name, recipient_phone: address.recipient_phone,
    address_line: address.address_line, province_code: address.province_code, district_code: address.district_code, ward_code: address.ward_code },
  items: [{ line_reference: "sku1", sku_id: "sku1", product_id: "p1", product_name: "Điện thoại", variant_attributes: { color: "Xanh" },
    image_url: "/media/test.png", quantity: 2, list_unit_price: money(110000), unit_price: money(100000), line_total: money(200000) }],
  pricing: { total_amount: money(200000), total_discount: money(0), final_amount: money(200000) },
  promotion: { promotion_id: null, campaign_revision: 0, discount: money(0) },
};
const voucher: VoucherOption = {
  promotion_id: "987654321012345678", source: "WALLET", required_action: "NONE", expected_campaign_revision: 7,
  code: "GIAM20", name: "Ưu đãi tháng mười", description: null,
  discount_rule: { type: "FIXED_VND", value: money(20000) }, min_order_amount: money(100000),
  valid_until: "2099-10-08T00:00:00Z", eligible: true, reason: "ELIGIBLE",
  estimated_discount: money(20000), estimated_final_amount: money(180000), is_applied: false,
};
const wallet = { wallet_id: "91", user_id: "buyer-a", promotion_id: voucher.promotion_id, status: "SAVED" as const,
  saved_at: "2026-10-10T00:00:00Z", expired_at: "2099-10-08T00:00:00Z", updated_at: "2026-10-10T00:00:00Z" };
const claimed = { claim_id: "92", acquired_at: "2026-10-10T00:00:00Z", campaign_revision_at_acquisition: 7, wallet };
const discounted: CheckoutPreview = { ...preview, checkout_token: secondToken,
  promotion: { promotion_id: voucher.promotion_id, campaign_revision: 7, discount: money(15000) },
  pricing: { total_amount: money(200000), total_discount: money(15000), final_amount: money(185000) } };
function optionsWith(...items: VoucherOption[]) {
  vi.mocked(checkoutApi.voucherOptions).mockResolvedValue({ checkout_token: token, applied_promotion_id: null, items, evaluated_at: "2026-10-10T00:00:00Z" });
}
const ready: SubmissionReady = { checkout_token: token, order_id: "12345", order_status: "PENDING_PAYMENT",
  payment: { payment_attempt_number: 1, payment_transaction_id: "tx1", usable: true, client_secret: "secret-memory-only", publishable_key: "pk_test", expires_at: "2099-10-07T00:35:00Z" } };
const pending = { data: { checkout_token: token, state: "RESULT_UNKNOWN" as const, status_url: `/api/v1/checkout/submissions/${token}` }, retryAfterSeconds: 60 };

function setup(initialToken: string | null = null) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const view = render(<StrictMode><QueryClientProvider client={client}><CheckoutPageContent initialToken={initialToken} /></QueryClientProvider></StrictMode>);
  return { client, ...view };
}
async function quoteReady() {
  const button = await screen.findByRole("button", { name: "Đặt hàng và thanh toán" });
  await waitFor(() => expect((button as HTMLButtonElement).disabled).toBe(false));
  return button;
}
beforeEach(() => {
  vi.resetAllMocks();
  useAuthStore.getState().setSession(`e30.${btoa(JSON.stringify({ sub: "buyer-a", roles: ["USER"] }))}.test`);
  vi.mocked(userApi.listAddresses).mockResolvedValue([address]);
  vi.mocked(checkoutApi.preview).mockResolvedValue(preview);
  vi.mocked(checkoutApi.submit).mockResolvedValue({ data: ready, retryAfterSeconds: null });
  vi.mocked(checkoutApi.submission).mockResolvedValue(pending);
  optionsWith();
  vi.mocked(promotionApi.saveVoucher).mockResolvedValue({ wallet, evaluation: null });
  vi.mocked(promotionApi.claimVoucher).mockResolvedValue({ data: claimed, retryAfterSeconds: null });
});
afterEach(() => { cleanup(); useAuthStore.getState().clear(); });

describe("checkout buyer flow", () => {
  it("F04 can save an ineligible public voucher for later without changing the active quote", async () => {
    optionsWith({ ...voucher, source: "PUBLIC", required_action: "SAVE", eligible: false, reason: "MIN_ORDER_NOT_MET" });
    setup(); await quoteReady();
    expect((screen.getByRole("button", { name: "Lưu và áp dụng" }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(screen.getByRole("button", { name: "Lưu voucher" }));
    expect(await screen.findByText(/Đã lưu voucher vào ví/)).toBeTruthy();
    expect(promotionApi.saveVoucher).toHaveBeenCalledWith(voucher.promotion_id, expect.any(AbortSignal));
    expect(checkoutApi.preview).toHaveBeenCalledTimes(1);
    expect(checkoutApi.submit).not.toHaveBeenCalled();
    expect(promotionApi.claimVoucher).not.toHaveBeenCalled();
  });
  it.each(["CAMPAIGN_NOT_ACTIVE", "PROMOTION_DISABLED", "VOUCHER_EXPIRED"] as const)("F04 does not offer saving when %s", async reason => {
    optionsWith({ ...voucher, source: "PUBLIC", required_action: "SAVE", eligible: false, reason });
    setup(); await quoteReady();
    expect(screen.queryByRole("button", { name: "Lưu voucher" })).toBeNull();
  });
  it("loads a wallet voucher, applies by previous token, and uses the authoritative new price", async () => {
    optionsWith(voucher);
    setup(); await quoteReady();
    vi.mocked(checkoutApi.preview).mockResolvedValue(discounted);
    fireEvent.click(await screen.findByRole("button", { name: "Áp dụng voucher" }));
    await waitFor(() => expect(checkoutApi.preview).toHaveBeenLastCalledWith({ delivery_address_id: address.id, previous_checkout_token: token, promotion_id: voucher.promotion_id }, expect.any(String), expect.any(AbortSignal)));
    await quoteReady();
    expect(screen.getAllByText("185.000 ₫")).toHaveLength(2);
    expect(screen.queryByText("180.000 ₫")).toBeNull();
    expect(promotionApi.saveVoucher).not.toHaveBeenCalled();
    expect(promotionApi.claimVoucher).not.toHaveBeenCalled();
    await waitFor(() => expect(checkoutApi.voucherOptions).toHaveBeenCalledWith(secondToken, expect.any(AbortSignal)));
  });
  it("presents structured voucher rules even when marketing text contradicts them", async () => {
    optionsWith({ ...voucher, name: "Giảm 10% đơn từ 200K", description: "Incorrect fixture description", discount_rule: { type: "PERCENTAGE", value_basis_points: 1500, max_discount: money(50000) } });
    setup(); await quoteReady();
    expect(await screen.findByRole("heading", { name: /Giảm 15%, tối đa 50\.000\s₫/ })).toBeTruthy();
    expect(screen.getByText(/Đơn tối thiểu 100.000/)).toBeTruthy();
    expect(screen.queryByText("Giảm 10% đơn từ 200K")).toBeNull();
    expect(screen.queryByText("Incorrect fixture description")).toBeNull();
    expect(screen.getAllByRole("button", { name: "Đặt hàng và thanh toán" })).toHaveLength(1);
  });
  it("saves public ownership before requesting the discounted quotation", async () => {
    optionsWith({ ...voucher, source: "PUBLIC", required_action: "SAVE" });
    setup(); await quoteReady();
    vi.mocked(checkoutApi.preview).mockResolvedValue(discounted);
    fireEvent.click(await screen.findByRole("button", { name: "Lưu và áp dụng" }));
    await quoteReady();
    await waitFor(() => expect(checkoutApi.preview).toHaveBeenCalledTimes(2));
    expect(promotionApi.saveVoucher).toHaveBeenCalledWith(voucher.promotion_id, expect.any(AbortSignal));
    expect(vi.mocked(promotionApi.saveVoucher).mock.invocationCallOrder[0]).toBeLessThan(vi.mocked(checkoutApi.preview).mock.invocationCallOrder[1]);
  });
  it("waits Retry-After on CLAIM_PENDING, then replays the exact claim body before applying", async () => {
    optionsWith({ ...voucher, source: "PUBLIC", required_action: "CLAIM" });
    vi.mocked(promotionApi.claimVoucher).mockResolvedValueOnce({ data: { code: "CLAIM_PENDING", status: "PENDING", promotion_id: voucher.promotion_id, retryable: true }, retryAfterSeconds: 1 })
      .mockResolvedValue({ data: claimed, retryAfterSeconds: null });
    setup(); await quoteReady();
    vi.mocked(checkoutApi.preview).mockResolvedValue(discounted);
    fireEvent.click(await screen.findByRole("button", { name: "Nhận và áp dụng" }));
    await screen.findByText(/Đang lưu hoặc nhận voucher/);
    expect(promotionApi.claimVoucher).toHaveBeenCalledTimes(1);
    expect(checkoutApi.preview).toHaveBeenCalledTimes(1);
    expect((screen.getByRole("button", { name: "Đặt hàng và thanh toán" }) as HTMLButtonElement).disabled).toBe(true);
    await waitFor(() => expect(checkoutApi.preview).toHaveBeenCalledTimes(2), { timeout: 2500 });
    expect(vi.mocked(promotionApi.claimVoucher).mock.calls.map((call) => call[0])).toEqual([
      { promotion_id: voucher.promotion_id, expected_campaign_revision: 7 },
      { promotion_id: voucher.promotion_id, expected_campaign_revision: 7 },
    ]);
  });
  it("keeps lost save results locked and retries the same promotion, without an early Preview", async () => {
    optionsWith({ ...voucher, source: "PUBLIC", required_action: "SAVE" });
    vi.mocked(promotionApi.saveVoucher).mockRejectedValueOnce(networkError());
    setup(); await quoteReady();
    fireEvent.click(await screen.findByRole("button", { name: "Lưu và áp dụng" }));
    const retry = await screen.findByRole("button", { name: "Tiếp tục kiểm tra voucher" });
    expect(checkoutApi.preview).toHaveBeenCalledTimes(1);
    expect((screen.getByRole("button", { name: "Đặt hàng và thanh toán" }) as HTMLButtonElement).disabled).toBe(true);
    vi.mocked(checkoutApi.preview).mockResolvedValue(discounted);
    fireEvent.click(retry);
    await waitFor(() => expect(checkoutApi.preview).toHaveBeenCalledTimes(2));
    expect(vi.mocked(promotionApi.saveVoucher).mock.calls.map((call) => call[0])).toEqual([voucher.promotion_id, voucher.promotion_id]);
  });
  it("honors acquisition Retry-After without requesting an early Preview", async () => {
    optionsWith({ ...voucher, source: "PUBLIC", required_action: "CLAIM" });
    vi.mocked(promotionApi.claimVoucher).mockRejectedValueOnce(httpError(503, problem("RUNTIME_NOT_READY", 503, { retryable: true }), { "retry-after": "60" }));
    setup(); await quoteReady();
    fireEvent.click(await screen.findByRole("button", { name: "Nhận và áp dụng" }));
    expect((await screen.findByRole("button", { name: "Thử lại sau 60 giây" }) as HTMLButtonElement).disabled).toBe(true);
    expect(checkoutApi.preview).toHaveBeenCalledTimes(1);
  });
  it("removes the applied voucher using null, then preserves a new explicit confirmation", async () => {
    optionsWith(voucher);
    setup(); await quoteReady();
    vi.mocked(checkoutApi.preview).mockResolvedValueOnce(discounted).mockResolvedValue({ ...preview, checkout_token: "41234567-1234-4234-8234-0123456789ab" });
    fireEvent.click(await screen.findByRole("button", { name: "Áp dụng voucher" }));
    fireEvent.click(await screen.findByRole("button", { name: "Bỏ voucher" }));
    await waitFor(() => expect(checkoutApi.preview).toHaveBeenLastCalledWith({ delivery_address_id: address.id, previous_checkout_token: secondToken, promotion_id: null }, expect.any(String), expect.any(AbortSignal)));
    await quoteReady(); expect(checkoutApi.submit).not.toHaveBeenCalled();
  });
  // The backend flags CLAIM_GATE_STALE retryable: true; the tray must still unlock so the user can reload it.
  it.each([false, true])("can reload options after a stale claim gate (retryable: %s) without applying an unowned voucher", async (retryable) => {
    optionsWith({ ...voucher, source: "PUBLIC", required_action: "CLAIM" });
    vi.mocked(promotionApi.claimVoucher).mockRejectedValueOnce(httpError(409, problem("CLAIM_GATE_STALE", 409, { retryable })));
    setup(); await quoteReady();
    fireEvent.click(await screen.findByRole("button", { name: "Nhận và áp dụng" }));
    await screen.findByText("Chương trình vừa thay đổi. Vui lòng tải lại rồi thử nhận voucher.");
    optionsWith({ ...voucher, source: "PUBLIC", required_action: "CLAIM", expected_campaign_revision: 8 });
    fireEvent.click(screen.getByRole("button", { name: "Tải lại voucher" }));
    await waitFor(() => expect(checkoutApi.voucherOptions).toHaveBeenCalledTimes(2));
    await quoteReady(); expect(checkoutApi.preview).toHaveBeenCalledTimes(1);
  });
  it("preserves the selected promotion when changing address and refreshing quotation", async () => {
    const other = { ...address, id: "31234567-1234-4234-8234-0123456789ab", recipient_name: "Nguyễn Bình", is_default: false };
    vi.mocked(userApi.listAddresses).mockResolvedValue([address, other]);
    optionsWith(voucher); setup(); await quoteReady();
    vi.mocked(checkoutApi.preview).mockResolvedValue(discounted);
    fireEvent.click(await screen.findByRole("button", { name: "Áp dụng voucher" }));
    await screen.findByRole("button", { name: "Bỏ voucher" }); await quoteReady();
    vi.mocked(checkoutApi.preview).mockResolvedValue({ ...discounted, address: { ...discounted.address, address_id: other.id } });
    fireEvent.click(screen.getByRole("radio", { name: /Nguyễn Bình/ }));
    await quoteReady();
    expect(checkoutApi.preview).toHaveBeenLastCalledWith({ delivery_address_id: other.id, previous_checkout_token: secondToken, promotion_id: voucher.promotion_id }, expect.any(String), expect.any(AbortSignal));
    fireEvent.click(screen.getByRole("button", { name: "Tải báo giá mới" }));
    await waitFor(() => expect(checkoutApi.preview).toHaveBeenLastCalledWith({ delivery_address_id: other.id, promotion_id: voucher.promotion_id }, expect.any(String), expect.any(AbortSignal)));
  });
  it("displays ineligibility in Vietnamese and disables applying that option", async () => {
    optionsWith({ ...voucher, eligible: false, reason: "MIN_ORDER_NOT_MET" });
    setup(); await quoteReady();
    await screen.findByText("Đơn hàng chưa đạt giá trị tối thiểu.");
    expect((screen.getByRole("button", { name: "Áp dụng voucher" }) as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByText("Đã có trong ví", { exact: false })).toBeTruthy();
  });
  it("allows checkout if voucher options are unavailable, and retries the tray separately", async () => {
    vi.mocked(checkoutApi.voucherOptions).mockRejectedValueOnce(httpError(503, problem("VOUCHER_OPTIONS_UNAVAILABLE", 503, { action: "RETRY", retryable: true })));
    setup(); await screen.findByText("Chưa tải được danh sách voucher. Bạn vẫn có thể đặt hàng không dùng voucher.");
    await quoteReady();
    fireEvent.click(screen.getByRole("button", { name: "Tải lại voucher" }));
    await screen.findByText("Chưa có voucher cho đơn này");
    expect(checkoutApi.preview).toHaveBeenCalledTimes(1);
    expect(screen.queryByText("English detail for developers")).toBeNull();
  });
  it("blocks a superseded token discovered by the tray and offers a fresh Preview", async () => {
    vi.mocked(checkoutApi.voucherOptions).mockRejectedValueOnce(httpError(410, problem("CHECKOUT_SUPERSEDED", 410, { action: "START_NEW_PREVIEW", retryable: false })));
    setup(); const fresh = await screen.findByRole("button", { name: "Xem báo giá mới" });
    expect((screen.getByRole("button", { name: "Đặt hàng và thanh toán" }) as HTMLButtonElement).disabled).toBe(true);
    vi.mocked(checkoutApi.preview).mockResolvedValue({ ...preview, checkout_token: secondToken });
    fireEvent.click(fresh); await quoteReady();
    expect(checkoutApi.submit).not.toHaveBeenCalled();
  });
  it("handles an invalid promotion by removing it in a fresh quotation", async () => {
    optionsWith(voucher); setup(); await quoteReady();
    vi.mocked(checkoutApi.preview).mockRejectedValueOnce(httpError(422, problem("PROMOTION_INVALID", 422, { action: "CHANGE_VOUCHER", promotion_reason: "BUDGET_EXHAUSTED", retryable: false })));
    fireEvent.click(await screen.findByRole("button", { name: "Áp dụng voucher" }));
    await screen.findByText("Voucher đã hết lượt sử dụng.");
    fireEvent.click(screen.getByRole("button", { name: "Xem báo giá mới" }));
    await quoteReady();
    expect(checkoutApi.preview).toHaveBeenLastCalledWith({ delivery_address_id: address.id }, expect.any(String), expect.any(AbortSignal));
  });
  it("preserves the voucher Preview body and key after a lost response", async () => {
    optionsWith(voucher); setup(); await quoteReady();
    vi.mocked(checkoutApi.preview).mockRejectedValueOnce(networkError()).mockResolvedValue(discounted);
    fireEvent.click(await screen.findByRole("button", { name: "Áp dụng voucher" }));
    const retry = await screen.findByRole("button", { name: "Thử lại cùng báo giá" });
    expect((screen.getByRole("button", { name: "Đặt hàng và thanh toán" }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(retry); await quoteReady();
    expect(vi.mocked(checkoutApi.preview).mock.calls[2].slice(0, 2)).toEqual(vi.mocked(checkoutApi.preview).mock.calls[1].slice(0, 2));
    expect(checkoutApi.submit).not.toHaveBeenCalled();
  });
  it("recovers a submission discovered by voucher options instead of creating another quotation", async () => {
    vi.mocked(checkoutApi.voucherOptions).mockRejectedValueOnce(httpError(409, problem("CHECKOUT_ALREADY_SUBMITTED", 409, { action: "VIEW_SUBMISSION", status_url: `/api/v1/checkout/submissions/${token}`, retryable: false })));
    setup();
    await waitFor(() => expect(checkoutApi.submission).toHaveBeenCalledWith(token, expect.any(AbortSignal)));
    expect(checkoutApi.preview).toHaveBeenCalledTimes(1);
    expect(checkoutApi.submit).not.toHaveBeenCalled();
  });
  it("cancels pending voucher acquisition on unmount", async () => {
    optionsWith({ ...voucher, source: "PUBLIC", required_action: "CLAIM" });
    vi.mocked(promotionApi.claimVoucher).mockImplementation(() => new Promise(() => {}));
    const view = setup(); await quoteReady();
    fireEvent.click(await screen.findByRole("button", { name: "Nhận và áp dụng" }));
    await waitFor(() => expect(promotionApi.claimVoucher).toHaveBeenCalled());
    const signal = vi.mocked(promotionApi.claimVoucher).mock.calls[0][1]!;
    view.unmount(); expect(signal.aborted).toBe(true);
  });
  it("creates one fresh preview under StrictMode, with only the selected address", async () => {
    setup(); await quoteReady();
    expect(checkoutApi.preview).toHaveBeenCalledTimes(1);
    expect(checkoutApi.preview).toHaveBeenCalledWith({ delivery_address_id: address.id }, expect.any(String), expect.any(AbortSignal));
    expect(screen.getByText("Điện thoại")).toBeTruthy();
  });
  it("offers address setup without calling Preview for an empty address book", async () => {
    vi.mocked(userApi.listAddresses).mockResolvedValue([]);
    setup(); expect(await screen.findByRole("link", { name: "Thêm địa chỉ nhận hàng" })).toBeTruthy();
    expect(checkoutApi.preview).not.toHaveBeenCalled();
  });
  it("supports retrying an address read failure", async () => {
    vi.mocked(userApi.listAddresses).mockRejectedValueOnce(networkError());
    setup(); fireEvent.click(await screen.findByRole("button", { name: "Thử lại" }));
    await quoteReady();
  });
  it("uses previous token for changing address, then locks Submit until the new quote arrives", async () => {
    const other = { ...address, id: "31234567-1234-4234-8234-0123456789ab", recipient_name: "Nguyễn Bình", is_default: false };
    vi.mocked(userApi.listAddresses).mockResolvedValue([address, other]);
    setup(); await quoteReady();
    vi.mocked(checkoutApi.preview).mockImplementationOnce(() => new Promise(() => {}));
    fireEvent.click(screen.getByRole("radio", { name: /Nguyễn Bình/ }));
    await waitFor(() => expect(checkoutApi.preview).toHaveBeenLastCalledWith({ delivery_address_id: other.id, previous_checkout_token: token }, expect.any(String), expect.any(AbortSignal)));
    expect((screen.getByRole("button", { name: "Đặt hàng và thanh toán" }) as HTMLButtonElement).disabled).toBe(true);
  });
  it("locks address edits and preserves the body and key after a lost Preview response", async () => {
    vi.mocked(checkoutApi.preview).mockRejectedValueOnce(networkError());
    setup(); const retry = await screen.findByRole("button", { name: "Thử lại cùng báo giá" });
    expect((screen.getByRole("radio") as HTMLInputElement).disabled || (screen.getByRole("radio").closest("fieldset") as HTMLFieldSetElement).disabled).toBe(true);
    fireEvent.click(retry); await quoteReady();
    expect(vi.mocked(checkoutApi.preview).mock.calls[1].slice(0, 2)).toEqual(vi.mocked(checkoutApi.preview).mock.calls[0].slice(0, 2));
  });
  it("honors Preview Retry-After and never exposes backend detail", async () => {
    vi.mocked(checkoutApi.preview).mockRejectedValueOnce(httpError(503, problem("CHECKOUT_UNAVAILABLE", 503, { action: "RETRY", retryable: true }), { "retry-after": "60" }));
    setup(); const retry = await screen.findByRole("button", { name: "Thử lại sau 60 giây" });
    expect((retry as HTMLButtonElement).disabled).toBe(true);
    expect(screen.queryByText("English detail for developers")).toBeNull();
  });
  it("does not allow submission of an expired quote", async () => {
    vi.mocked(checkoutApi.preview).mockResolvedValue({ ...preview, expires_at: "2000-01-01T00:00:00Z" });
    setup(); await screen.findByText("Báo giá đã hết hạn. Tải báo giá mới trước khi đặt hàng.");
    expect((screen.getByRole("button", { name: "Đặt hàng và thanh toán" }) as HTMLButtonElement).disabled).toBe(true);
  });
  it("uses a fresh preview after a changed active checkout and requires a new confirmation", async () => {
    vi.mocked(checkoutApi.preview).mockRejectedValueOnce(httpError(409, problem("ACTIVE_CHECKOUT_CHANGED", 409, { action: "START_NEW_PREVIEW" })));
    setup(); fireEvent.click(await screen.findByRole("button", { name: "Xem báo giá mới" })); await quoteReady();
    expect(vi.mocked(checkoutApi.preview).mock.calls[1][0]).toEqual({ delivery_address_id: address.id });
    expect(checkoutApi.submit).not.toHaveBeenCalled();
  });
  it("retains a recovery URL before Submit and connects to the existing Stripe route", async () => {
    setup(); fireEvent.click(await quoteReady());
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/orders/12345/payment?attempt=1"));
    expect(checkoutApi.submit).toHaveBeenCalledTimes(1);
    expect(checkoutApi.submit).toHaveBeenCalledWith(token, expect.anything());
    expect(replace.mock.calls[0][0]).toBe(`/checkout?submission=${token}`);
    expect(JSON.stringify(replace.mock.calls)).not.toContain("secret-memory-only");
  });
  it("goes to order detail when payment data is unusable", async () => {
    vi.mocked(checkoutApi.submit).mockResolvedValue({ data: { ...ready, payment: { ...ready.payment, usable: false, client_secret: null, publishable_key: null } }, retryAfterSeconds: null });
    setup(); fireEvent.click(await quoteReady());
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/orders/12345"));
  });
  it("recovers an unknown Submit outcome by GET without sending another order or Preview", async () => {
    vi.mocked(checkoutApi.submit).mockRejectedValueOnce(networkError());
    setup(); fireEvent.click(await quoteReady());
    await screen.findByText("Chưa xác định được kết quả. Hệ thống sẽ tiếp tục kiểm tra cùng yêu cầu.");
    expect(checkoutApi.submission).toHaveBeenCalledWith(token, expect.any(AbortSignal));
    expect(checkoutApi.submit).toHaveBeenCalledTimes(1);
    expect(checkoutApi.preview).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole("button", { name: "Tải báo giá mới" })).toBeNull();
  });
  it("resumes after reload using only the recovery token, without a fresh Preview", async () => {
    vi.mocked(checkoutApi.submission).mockResolvedValue({ data: ready, retryAfterSeconds: null });
    setup(token); await waitFor(() => expect(replace).toHaveBeenCalledWith("/orders/12345/payment?attempt=1"));
    expect(checkoutApi.preview).not.toHaveBeenCalled();
    expect(checkoutApi.submit).not.toHaveBeenCalled();
  });
  it("polls a 202 at the suggested interval and eventually follows the ready result", async () => {
    vi.mocked(checkoutApi.submit).mockResolvedValue(pending);
    vi.mocked(checkoutApi.submission).mockResolvedValueOnce({ ...pending, retryAfterSeconds: 1 })
      .mockResolvedValue({ data: ready, retryAfterSeconds: null });
    setup(); fireEvent.click(await quoteReady());
    await screen.findByText("Chưa xác định được kết quả. Hệ thống sẽ tiếp tục kiểm tra cùng yêu cầu.");
    expect(checkoutApi.submission).toHaveBeenCalledTimes(1);
    expect(replace).not.toHaveBeenCalledWith("/orders/12345/payment?attempt=1");
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/orders/12345/payment?attempt=1"), { timeout: 2_500 });
    expect(checkoutApi.submit).toHaveBeenCalledTimes(1);
  });
  it("keeps recovery locked and honors Retry-After after a status dependency failure", async () => {
    vi.mocked(checkoutApi.submission).mockRejectedValue(httpError(503, problem("CHECKOUT_UNAVAILABLE", 503, { action: "RETRY", retryable: true }), { "retry-after": "60" }));
    setup(token);
    expect((await screen.findByRole("button", { name: "Thử lại sau 60 giây" }) as HTMLButtonElement).disabled).toBe(true);
    expect(screen.queryByRole("button", { name: "Xem báo giá mới" })).toBeNull();
    expect(checkoutApi.submit).not.toHaveBeenCalled();
    expect(checkoutApi.preview).not.toHaveBeenCalled();
  });
  it("follows SUBMISSION_IN_PROGRESS by its validated status token", async () => {
    vi.mocked(checkoutApi.preview).mockRejectedValueOnce(httpError(409, problem("SUBMISSION_IN_PROGRESS", 409, { action: "VIEW_SUBMISSION", status_url: `/api/v1/checkout/submissions/${secondToken}` })));
    setup(); await waitFor(() => expect(checkoutApi.submission).toHaveBeenCalledWith(secondToken, expect.any(AbortSignal)));
    expect(checkoutApi.submit).not.toHaveBeenCalled();
  });
  it("permits a fresh quote only after a definitive Submit conflict", async () => {
    vi.mocked(checkoutApi.submit).mockRejectedValueOnce(httpError(409, problem("PRICE_CHANGED", 409, { action: "START_NEW_PREVIEW", changed_fields: ["PRICE"] })));
    setup(); fireEvent.click(await quoteReady());
    fireEvent.click(await screen.findByRole("button", { name: "Xem báo giá mới" })); await quoteReady();
    expect(vi.mocked(checkoutApi.preview).mock.calls[1][0]).toEqual({ delivery_address_id: address.id });
    expect(checkoutApi.submission).not.toHaveBeenCalled();
  });
  it("resends only the same token when recovery definitively says never submitted", async () => {
    vi.mocked(checkoutApi.submission).mockRejectedValue(httpError(404, problem("SUBMISSION_NOT_FOUND", 404, { action: "NONE" })));
    setup(token); fireEvent.click(await screen.findByRole("button", { name: "Gửi lại cùng yêu cầu đặt hàng" }));
    await waitFor(() => expect(checkoutApi.submit).toHaveBeenCalledWith(token, expect.anything()));
    expect(checkoutApi.preview).not.toHaveBeenCalled();
  });
  it.each(["CHECKOUT_EXPIRED", "CHECKOUT_SUPERSEDED", "PRICE_CHANGED"])("replaces the cached recovery 404 with terminal Submit %s and stops resending", async (code) => {
    vi.mocked(checkoutApi.submission).mockRejectedValue(httpError(404, problem("SUBMISSION_NOT_FOUND", 404, { action: "NONE" })));
    vi.mocked(checkoutApi.submit).mockRejectedValueOnce(httpError(code === "PRICE_CHANGED" ? 409 : 410, problem(code, code === "PRICE_CHANGED" ? 409 : 410, { action: "START_NEW_PREVIEW", retryable: false })));
    setup(token);
    fireEvent.click(await screen.findByRole("button", { name: "Gửi lại cùng yêu cầu đặt hàng" }));
    const fresh = await screen.findByRole("button", { name: "Xem báo giá mới" });
    expect(screen.queryByRole("button", { name: "Gửi lại cùng yêu cầu đặt hàng" })).toBeNull();
    expect(checkoutApi.submit).toHaveBeenCalledTimes(1);
    expect(checkoutApi.preview).not.toHaveBeenCalled();
    fireEvent.click(fresh);
    await quoteReady();
    expect(checkoutApi.preview).toHaveBeenCalledTimes(1);
    expect(checkoutApi.submit).toHaveBeenCalledTimes(1);
  });
  it("aborts status polling on unmount", async () => {
    vi.mocked(checkoutApi.submission).mockImplementation(() => new Promise(() => {}));
    const view = setup(token);
    await waitFor(() => expect(checkoutApi.submission).toHaveBeenCalled());
    const signal = vi.mocked(checkoutApi.submission).mock.calls.at(-1)![1]!;
    view.unmount(); expect(signal.aborted).toBe(true);
  });
  it("keeps a definitive recovery failure actionable after the 90-second poll deadline", async () => {
    const timers = vi.spyOn(window, "setTimeout");
    try {
      vi.mocked(checkoutApi.submission).mockRejectedValue(httpError(409, problem("PAYMENT_INITIATION_REJECTED", 409, { action: "START_NEW_PREVIEW", retryable: false })));
      setup(token);
      await screen.findByRole("button", { name: "Xem báo giá mới" });
      const deadline = timers.mock.calls.find(([, delay]) => typeof delay === "number" && delay > 89_000 && delay <= 90_000)?.[0];
      expect(typeof deadline).toBe("function");
      act(() => { if (typeof deadline === "function") deadline(); });
      expect(screen.getByRole("heading", { name: "Cần xem lại yêu cầu đặt hàng" })).toBeTruthy();
      expect(screen.queryByRole("button", { name: "Kiểm tra lại kết quả" })).toBeNull();
      expect(screen.queryByText(/Chưa có kết quả cuối/)).toBeNull();
      expect(checkoutApi.submit).not.toHaveBeenCalled();
      expect(checkoutApi.preview).not.toHaveBeenCalled();
    } finally {
      timers.mockRestore();
    }
  });
});
