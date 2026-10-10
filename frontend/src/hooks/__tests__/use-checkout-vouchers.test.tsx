// @vitest-environment jsdom
import type { ReactNode } from "react";
import { act, cleanup, renderHook } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, describe, expect, it, vi } from "vitest";
import { useCheckoutVouchers } from "@/hooks/use-checkout-vouchers";
import { promotionApi } from "@/lib/api/promotion";
import type { VoucherOption } from "@/lib/api/checkout";
import { PollTimeoutError } from "@/lib/api/operation";
import { useAuthStore } from "@/store/useAuthStore";

vi.mock("@/lib/api/checkout", () => ({ checkoutApi: { voucherOptions: vi.fn() } }));
vi.mock("@/lib/api/promotion", () => ({ promotionApi: { claimVoucher: vi.fn(), saveVoucher: vi.fn() } }));
const option = { promotion_id: "123", expected_campaign_revision: 7, required_action: "CLAIM" } as VoucherOption;
afterEach(() => { cleanup(); vi.useRealTimers(); vi.resetAllMocks(); });
describe("bounded voucher acquisition", () => {
  it("stops before an early POST when Retry-After exceeds the polling deadline, retaining the intent", async () => {
    vi.useFakeTimers();
    useAuthStore.getState().setSession(`e30.${btoa(JSON.stringify({ sub: "buyer", roles: ["USER"] }))}.test`);
    vi.mocked(promotionApi.claimVoucher).mockResolvedValue({
      data: { code: "CLAIM_PENDING", status: "PENDING", promotion_id: "123", retryable: true }, retryAfterSeconds: 60,
    });
    const onApply = vi.fn();
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>;
    const { result } = renderHook(() => useCheckoutVouchers("token", false, onApply), { wrapper });
    await act(async () => { result.current.acquire.mutate(option); await vi.advanceTimersByTimeAsync(1); });
    await act(async () => { await vi.advanceTimersByTimeAsync(30_010); });
    expect(promotionApi.claimVoucher).toHaveBeenCalledTimes(1);
    expect(result.current.acquire.error).toBeInstanceOf(PollTimeoutError);
    expect(result.current.busy).toBe(true);
    expect(result.current.acquire.variables).toBe(option);
    expect(result.current.claimRetryAt).toBeGreaterThan(Date.now());
    expect(onApply).not.toHaveBeenCalled();
    client.clear(); useAuthStore.getState().clear();
  });
});
