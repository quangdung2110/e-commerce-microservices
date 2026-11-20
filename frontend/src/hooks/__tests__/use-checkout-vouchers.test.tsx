// @vitest-environment jsdom
import type { ReactNode } from "react";
import { act, cleanup, renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, describe, expect, it, vi } from "vitest";
import { useCheckoutVouchers } from "@/hooks/use-checkout-vouchers";
import { checkoutApi } from "@/lib/api/checkout";
import { promotionApi } from "@/lib/api/promotion";
import type { VoucherOption } from "@/lib/api/checkout";
import { PollTimeoutError } from "@/lib/api/operation";
import { useAuthStore } from "@/store/useAuthStore";

vi.mock("@/lib/api/checkout", () => ({ checkoutApi: { voucherOptions: vi.fn() } }));
vi.mock("@/lib/api/promotion", () => ({ promotionApi: { claimVoucher: vi.fn(), saveVoucher: vi.fn() } }));
const option = { promotion_id: "123", expected_campaign_revision: 7, required_action: "CLAIM" } as VoucherOption;
afterEach(() => { cleanup(); vi.useRealTimers(); vi.resetAllMocks(); });
describe("bounded voucher acquisition", () => {
  it("F04 saves only without applying or changing the quote, and refreshes wallet ownership", async () => {
    useAuthStore.getState().setSession(`e30.${btoa(JSON.stringify({ sub: "buyer", roles: ["USER"] }))}.test`);
    vi.mocked(checkoutApi.voucherOptions).mockResolvedValue({ items: [] } as never);
    vi.mocked(promotionApi.saveVoucher).mockResolvedValue({ wallet: { status: "SAVED" }, evaluation: null } as never);
    const onApply = vi.fn();
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>;
    const { result } = renderHook(() => useCheckoutVouchers("token", true, onApply), { wrapper });
    await waitFor(() => expect(result.current.options.isSuccess).toBe(true));
    await act(async () => { result.current.acquire.mutate({ ...option, required_action: "SAVE", saveOnly: true }); });
    await waitFor(() => expect(result.current.acquire.isSuccess).toBe(true));
    expect(promotionApi.saveVoucher).toHaveBeenCalledWith("123", expect.any(AbortSignal));
    expect(promotionApi.claimVoucher).not.toHaveBeenCalled();
    expect(onApply).not.toHaveBeenCalled();
    await waitFor(() => expect(checkoutApi.voucherOptions).toHaveBeenCalledTimes(2));
    client.clear(); useAuthStore.getState().clear();
  });
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

  it("treats a stale claim gate flagged retryable as a definitive rejection that leaves the tray free to reload", async () => {
    useAuthStore.getState().setSession(`e30.${btoa(JSON.stringify({ sub: "buyer", roles: ["USER"] }))}.test`);
    vi.mocked(checkoutApi.voucherOptions).mockResolvedValue({ items: [] } as never);
    vi.mocked(promotionApi.claimVoucher).mockRejectedValue({
      problem: { type: "about:blank", title: "stale", status: 409, detail: "stale", instance: "/", code: "CLAIM_GATE_STALE", retryable: true },
    });
    const onApply = vi.fn();
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>;
    const { result } = renderHook(() => useCheckoutVouchers("token", true, onApply), { wrapper });
    await waitFor(() => expect(result.current.options.isSuccess).toBe(true));
    await act(async () => { result.current.acquire.mutate(option); });
    await waitFor(() => expect(result.current.acquire.isError).toBe(true));
    expect(promotionApi.claimVoucher).toHaveBeenCalledTimes(1);
    expect(result.current.unresolved).toBe(false);
    expect(result.current.busy).toBe(false);
    expect(onApply).not.toHaveBeenCalled();
    client.clear(); useAuthStore.getState().clear();
  });
});
