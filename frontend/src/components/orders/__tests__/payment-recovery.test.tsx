// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { useEffect } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { PaymentContent } from "@/components/orders/payment-content";
import { PaymentCompleteContent } from "@/components/orders/payment-complete-content";
import type { OrderStatus, PaymentStatus } from "@/lib/api/order";
import { confirmationKey, markConfirmationUnknown } from "@/lib/payment-confirmation";

const f = vi.hoisted(() => ({
  orderStatus: "PENDING_PAYMENT" as OrderStatus, paymentStatus: "PENDING" as PaymentStatus,
  cancelReason: null as string | null, load: vi.fn(), refetch: vi.fn(), confirm: vi.fn(), submit: vi.fn(),
  sdkReady: true, elementReady: true, loadError: undefined as (() => void) | undefined,
}));
vi.mock("next-themes", () => ({ useTheme: () => ({ resolvedTheme: "light" }) }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock("@stripe/stripe-js", () => ({ loadStripe: f.load }));
vi.mock("@stripe/react-stripe-js", () => ({
  Elements: ({ children }: { children: React.ReactNode }) => children,
  PaymentElement: ({ onReady, onLoadError }: { onReady: () => void; onLoadError: () => void }) => {
    useEffect(() => { if (f.elementReady) onReady(); }, [onReady]);
    f.loadError = onLoadError;
    return <div>Stripe fixture</div>;
  },
  useStripe: () => f.sdkReady ? { confirmPayment: f.confirm } : null,
  useElements: () => f.sdkReady ? { submit: f.submit } : null,
}));
vi.mock("@/hooks/use-orders", () => ({
  useOrder: () => ({ data: { order_id: "1", order_status: f.orderStatus, payment_status: f.paymentStatus,
    cancel_reason: f.cancelReason, final_amount: { amount_minor: 690000, currency: "VND" },
    payment: { active_attempt_number: 1, active_payment_transaction_id: "txn_fixture", payment_deadline_at: "2030-01-01T00:00:00Z" } }, refetch: f.refetch }),
  usePaymentAttempt: () => ({ data: { order_id: "1", attempt_number: 1, payment_transaction_id: "txn_fixture", status: "PENDING",
    client_secret: "fixture_secret", publishable_key: "pk_test_fixture", expires_at: "2030-01-01T00:00:00Z",
    amount: { amount_minor: 690000, currency: "VND" } } }),
}));
beforeEach(() => {
  f.orderStatus = "PENDING_PAYMENT"; f.paymentStatus = "PENDING"; f.cancelReason = null;
  f.sdkReady = true; f.elementReady = true; f.loadError = undefined;
  for (const mock of [f.load, f.refetch, f.confirm, f.submit]) mock.mockReset();
  f.load.mockResolvedValue({});
  markConfirmationUnknown(confirmationKey("1", 1), false);
});
afterEach(() => { cleanup(); vi.useRealTimers(); });

describe("payment callback follows Order and financial state", () => {
  const cases: { order: OrderStatus; payment: PaymentStatus; reason?: string; title: string; refresh?: boolean }[] = [
    { order: "CANCELLED", payment: "PENDING", reason: "PAYMENT_TIMEOUT", title: "Đơn hàng đã hết hạn thanh toán" },
    { order: "CANCELLED", payment: "PENDING", reason: "USER_CANCEL", title: "Đơn hàng đã hủy" },
    { order: "CANCELLED", payment: "FAILED", title: "Đơn hàng đã hủy" },
    { order: "CANCELLED", payment: "PAID", title: "Đơn hàng cần kiểm tra thanh toán" },
    { order: "CANCELLING", payment: "PENDING", title: "Đang hủy đơn hàng", refresh: true },
    { order: "PENDING_PAYMENT", payment: "PENDING", title: "Đang xác nhận thanh toán", refresh: true },
    { order: "PENDING_PAYMENT", payment: "FAILED", title: "Thanh toán chưa thành công" },
    { order: "PROCESSING", payment: "PAID", title: "Thanh toán thành công" },
    { order: "REFUNDED", payment: "REFUNDED", title: "Đã hoàn tiền" },
    { order: "CANCELLED", payment: "REFUNDING", title: "Đang hoàn tiền", refresh: true },
    { order: "RETURN_REQUESTED", payment: "REFUND_FAILED", title: "Hoàn tiền chưa hoàn tất" },
    { order: "FINANCIAL_RECONCILIATION_REQUIRED", payment: "PAID", title: "Thanh toán đang được đối soát" },
  ];
  it.each(cases)("$order / $payment presents $title", ({ order, payment, reason, title, refresh }) => {
    f.orderStatus = order; f.paymentStatus = payment; f.cancelReason = reason ?? null;
    render(<PaymentCompleteContent orderId="1" />);
    expect(screen.getByRole("heading", { name: title })).toBeDefined();
    expect(screen.getByRole("link", { name: "Xem chi tiết đơn" }).getAttribute("href")).toBe("/orders/1");
    expect(screen.queryByText(/Stripe đã nhận yêu cầu/)).toBeNull();
    const check = screen.queryByRole("button", { name: "Kiểm tra lại" });
    expect(Boolean(check)).toBe(Boolean(refresh));
    if (check) { fireEvent.click(check); expect(f.refetch).toHaveBeenCalledOnce(); }
  });
});

describe("Stripe initialization recovery", () => {
  const open = () => render(<PaymentContent orderId="1" requestedAttempt={1} />);
  const expectRecovery = () => {
    expect(screen.getByText("Không tải được Stripe")).toBeDefined();
    expect(screen.getByRole("button", { name: "Thử tải lại Stripe" })).toBeDefined();
    expect(screen.queryByRole("button", { name: "Thanh toán an toàn" })).toBeNull();
    expect(f.confirm).not.toHaveBeenCalled();
  };
  it.each(["null", "reject", "throw"])("offers recovery when loadStripe returns %s", async (result) => {
    if (result === "null") f.load.mockResolvedValue(null);
    if (result === "reject") f.load.mockRejectedValue(new Error("CDN unavailable"));
    if (result === "throw") f.load.mockImplementation(() => { throw new Error("invalid key"); });
    open(); await screen.findByText("Không tải được Stripe"); expectRecovery();
  });
  it("bounds an unresolved SDK load to 20 seconds and preserves unknown confirmation", async () => {
    vi.useFakeTimers(); f.sdkReady = false; f.elementReady = false; f.load.mockReturnValue(new Promise(() => {}));
    markConfirmationUnknown(confirmationKey("1", 1), true); open();
    expect(screen.getByText("Đang tải kết nối Stripe")).toBeDefined();
    await act(async () => { vi.advanceTimersByTime(20_000); }); expectRecovery();
    expect(sessionStorage.getItem(confirmationKey("1", 1))).toBe("1");
  });
  it("bounds a Payment Element that never becomes ready even after SDK loads", async () => {
    vi.useFakeTimers(); f.elementReady = false; open();
    expect(screen.getByRole("button", { name: "Thanh toán an toàn" }).hasAttribute("disabled")).toBe(true);
    await act(async () => { vi.advanceTimersByTime(20_000); }); expectRecovery();
  });
  it("offers recovery on Payment Element loaderror", async () => {
    open(); await act(async () => { f.loadError!(); }); expectRecovery();
  });
  it("clears the loading deadline after the Element is ready", async () => {
    vi.useFakeTimers(); open(); await act(async () => { vi.advanceTimersByTime(20_000); });
    expect(screen.queryByText("Không tải được Stripe")).toBeNull();
    expect(screen.getByRole("button", { name: "Thanh toán an toàn" }).hasAttribute("disabled")).toBe(false);
  });
});
