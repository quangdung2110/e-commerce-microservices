import { beforeEach, describe, expect, it, vi } from "vitest";
import { api } from "@/lib/api/client";
import { checkoutApi } from "@/lib/api/checkout";
import { checkoutToken, submissionTokenFromUrl, submissionDestination } from "@/lib/checkout";

vi.mock("@/lib/api/client", () => ({ api: { post: vi.fn(), get: vi.fn() } }));
const token = "01234567-1234-4234-8234-0123456789ab";
beforeEach(() => vi.resetAllMocks());
describe("Checkout HTTP contract", () => {
  it("reads options by token with cancellation, without sending browser cart context", async () => {
    const signal = new AbortController().signal;
    vi.mocked(api.get).mockResolvedValue({ data: { items: [] } });
    await checkoutApi.voucherOptions(token, signal);
    expect(api.get).toHaveBeenCalledWith(`/api/v1/checkout/previews/${token}/voucher-options`, { signal, timeout: 8_000 });
  });
  it("sends Preview with its idempotency key and no client prices", async () => {
    vi.mocked(api.post).mockResolvedValue({ data: { checkout_token: token } });
    await checkoutApi.preview({ delivery_address_id: "address1" }, token);
    expect(api.post).toHaveBeenCalledWith("/api/v1/checkout/preview", { delivery_address_id: "address1" }, expect.objectContaining({ headers: { "Idempotency-Key": token } }));
  });
  it("uses only checkout_token for Submit and preserves Retry-After for 202", async () => {
    vi.mocked(api.post).mockResolvedValue({ data: { state: "PREPARING" }, headers: { "retry-after": "3" }, status: 202 });
    const result = await checkoutApi.submit(token);
    expect(result.retryAfterSeconds).toBe(3);
    expect(api.post).toHaveBeenCalledWith("/api/v1/checkout/submit", { checkout_token: token }, { timeout: 25_000 });
  });
  it("uses the canonical token endpoint and propagates cancellation", async () => {
    const signal = new AbortController().signal;
    vi.mocked(api.get).mockResolvedValue({ data: { state: "RESULT_UNKNOWN" }, headers: {} });
    await checkoutApi.submission(token, signal);
    expect(api.get).toHaveBeenCalledWith(`/api/v1/checkout/submissions/${token}`, { signal, timeout: 25_000 });
  });
  it("rejects unsafe recovery URLs and malformed query tokens", () => {
    expect(submissionTokenFromUrl(`/api/v1/checkout/submissions/${token}`)).toBe(token);
    for (const url of [`https://other.test/api/v1/checkout/submissions/${token}`, `//other.test/${token}`, `/api/v1/orders/${token}`, `/api/v1/checkout/submissions/${token}?x=1`]) expect(submissionTokenFromUrl(url)).toBeNull();
    expect(checkoutToken([token])).toBeNull();
    expect(checkoutToken("invalid")).toBeNull();
  });
  it("never treats a usable flag without payment credentials as ready for Stripe", () => {
    expect(submissionDestination({ checkout_token: token, order_id: "12", order_status: "PENDING_PAYMENT", payment: { usable: true, payment_attempt_number: 1, payment_transaction_id: "tx", client_secret: null, publishable_key: null, expires_at: null } })).toBe("/orders/12");
  });
});
