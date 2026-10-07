// @vitest-environment jsdom
import { StrictMode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { CheckoutPageContent } from "@/components/checkout/checkout-page-content";
import { checkoutApi, type CheckoutPreview, type SubmissionReady } from "@/lib/api/checkout";
import { userApi, type Address } from "@/lib/api/user";
import { httpError, networkError, problem } from "@/lib/api/__tests__/helpers";
import { useAuthStore } from "@/store/useAuthStore";

const { replace } = vi.hoisted(() => ({ replace: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ replace }) }));
vi.mock("@/lib/api/checkout", () => ({ checkoutApi: { preview: vi.fn(), submit: vi.fn(), submission: vi.fn() } }));
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
});
afterEach(() => { cleanup(); useAuthStore.getState().clear(); });

describe("checkout buyer flow", () => {
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
  it("aborts status polling on unmount", async () => {
    vi.mocked(checkoutApi.submission).mockImplementation(() => new Promise(() => {}));
    const view = setup(token);
    await waitFor(() => expect(checkoutApi.submission).toHaveBeenCalled());
    const signal = vi.mocked(checkoutApi.submission).mock.calls.at(-1)![1]!;
    view.unmount(); expect(signal.aborted).toBe(true);
  });
});
