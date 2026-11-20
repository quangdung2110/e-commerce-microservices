import { beforeEach, describe, expect, it, vi } from "vitest";
import { api } from "@/lib/api/client";
import { promotionApi } from "@/lib/api/promotion";
import { isClaimCommitted, voucherDiscount, voucherReason } from "@/lib/checkout-voucher";
import type { VoucherOption } from "@/lib/api/checkout";

vi.mock("@/lib/api/client", () => ({ api: { get: vi.fn(), post: vi.fn(), put: vi.fn() } }));
beforeEach(() => vi.resetAllMocks());
describe("customer Promotion contract", () => {
  it("reads only the authenticated wallet with filters and opaque cursor, without checkout context", async () => {
    const signal = new AbortController().signal;
    const data = { items: [], next_cursor: null, as_of: "2026-10-20T00:00:00Z" };
    vi.mocked(api.get).mockResolvedValue({ data });
    expect(await promotionApi.listMyWallet({ status: "USED", cursor: "opaque", limit: 20 }, signal)).toEqual(data);
    expect(api.get).toHaveBeenCalledWith("/api/v1/me/promotion-vouchers", { params: { status: "USED", cursor: "opaque", limit: 20 }, signal });
  });
  it("saves with an empty body and propagates cancellation, without sending identity or prices", async () => {
    const signal = new AbortController().signal;
    vi.mocked(api.put).mockResolvedValue({ data: { evaluation: null } });
    await promotionApi.saveVoucher("123/45", signal);
    expect(api.put).toHaveBeenCalledWith("/api/v1/me/promotion-vouchers/123%2F45", {}, { signal, timeout: 8_000 });
  });
  it("claims using opaque string IDs and expected revision, preserving 202 Retry-After", async () => {
    const signal = new AbortController().signal;
    const input = { promotion_id: "987654321012345678", expected_campaign_revision: 7 };
    const data = { code: "CLAIM_PENDING", status: "PENDING", promotion_id: input.promotion_id, retryable: true };
    vi.mocked(api.post).mockResolvedValue({ data, headers: { "retry-after": "3" }, status: 202 });
    expect(await promotionApi.claimVoucher(input, signal)).toEqual({ data, retryAfterSeconds: 3 });
    expect(api.post).toHaveBeenCalledWith("/api/v1/me/promotion-voucher-claims", input, { signal, timeout: 8_000 });
    expect(isClaimCommitted(data as Parameters<typeof isClaimCommitted>[0])).toBe(false);
  });
  it("has a safe Vietnamese fallback for future eligibility reasons", () => {
    expect(voucherReason("FUTURE_REASON")).toBe(voucherReason("UNKNOWN"));
  });
  it("formats fractional percentage and caps in VND without dividing minor units", () => {
    expect(voucherDiscount({ discount_rule: { type: "PERCENTAGE", value_basis_points: 1250, max_discount: { amount_minor: 20000, currency: "VND" } } } as VoucherOption).replace(/\s/g, " ")).toBe("Giảm 12,5%, tối đa 20.000 ₫");
  });
});
