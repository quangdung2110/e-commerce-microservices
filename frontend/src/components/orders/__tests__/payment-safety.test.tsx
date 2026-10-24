// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { useEffect } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { PaymentContent } from "@/components/orders/payment-content";
import type { OrderDetail, PaymentAttempt } from "@/lib/api/order";
import { confirmationKey, markConfirmationUnknown } from "@/lib/payment-confirmation";

function orderFixture(): OrderDetail {
  return { order_id: "1", buyer_id: "qa", order_status: "PENDING_PAYMENT", payment_status: "PENDING", buyer_tab: "TO_PAY",
    total_amount: { amount_minor: 690000, currency: "VND" }, total_discount: { amount_minor: 0, currency: "VND" },
    final_amount: { amount_minor: 690000, currency: "VND" }, items: [], promotions: [], refund_case: null,
    payment: { status: "PENDING", active_attempt_number: 1, active_payment_transaction_id: "txn_test", payment_deadline_at: "2030-01-01T00:00:00Z" },
    available_actions: { can_cancel: true, can_retry_payment: false, can_return: false }, created_at: "2026-01-01T00:00:00Z", updated_at: "2026-01-01T00:00:00Z" };
}
function attemptFixture(): PaymentAttempt {
  return { order_id: "1", attempt_number: 1, payment_transaction_id: "txn_test", status: "PENDING",
    amount: { amount_minor: 690000, currency: "VND" }, client_secret: "fixture_secret", publishable_key: "pk_test_fixture",
    expires_at: "2030-01-01T00:00:00Z", created_at: "2026-01-01T00:00:00Z" };
}
const f = vi.hoisted(() => ({ order: {} as OrderDetail, attempt: {} as PaymentAttempt,
  readOrder: vi.fn(), readAttempt: vi.fn(), submit: vi.fn(), confirm: vi.fn(), push: vi.fn() }));
vi.mock("next-themes", () => ({ useTheme: () => ({ resolvedTheme: "light" }) }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ push: f.push }) }));
vi.mock("@stripe/stripe-js", () => ({ loadStripe: () => Promise.resolve({}) }));
vi.mock("@stripe/react-stripe-js", () => ({ Elements: ({ children }: { children: React.ReactNode }) => children,
  PaymentElement: ({ onReady }: { onReady: () => void }) => { useEffect(onReady, [onReady]); return <div>Stripe fixture</div>; }, useStripe: () => ({ confirmPayment: f.confirm }), useElements: () => ({ submit: f.submit }) }));
vi.mock("@/hooks/use-orders", () => ({
  useOrder: () => ({ data: f.order, refetch: f.readOrder }),
  usePaymentAttempt: () => ({ data: f.attempt, refetch: f.readAttempt }),
}));
beforeEach(() => {
  f.order = orderFixture(); f.attempt = attemptFixture();
  for (const mock of [f.readOrder, f.readAttempt, f.submit, f.confirm, f.push]) mock.mockReset();
  f.readOrder.mockImplementation(async () => ({ data: f.order, isError: false }));
  f.readAttempt.mockImplementation(async () => ({ data: f.attempt, isError: false }));
  f.submit.mockResolvedValue({}); f.confirm.mockResolvedValue({});
  markConfirmationUnknown(confirmationKey("1", 1), false);
});
afterEach(() => { cleanup(); vi.useRealTimers(); });
const open = () => render(<PaymentContent orderId="1" requestedAttempt={1} />);
const confirmButton = () => screen.getByRole("button", { name: "Thanh toán an toàn" });

describe("payment deadline and fresh state", () => {
  it("removes an open form at the immutable Order deadline without a reload", async () => {
    vi.useFakeTimers(); vi.setSystemTime(new Date("2026-10-14T09:00:00Z"));
    f.order.payment.payment_deadline_at = "2026-10-14T09:00:05Z";
    const view = open(); expect(confirmButton()).toBeDefined();
    expect(screen.getByText("Hạn thanh toán").nextElementSibling?.textContent).toContain("16:00");
    await act(async () => { vi.advanceTimersByTime(5000); });
    expect(screen.queryByRole("button", { name: "Thanh toán an toàn" })).toBeNull();
    expect(f.confirm).not.toHaveBeenCalled(); view.unmount();
  });
  it.each(["deadline", "credential", "missing-expiry", "inactive", "wrong-transaction", "wrong-amount", "cancelled"])("fails closed for %s", (reason) => {
    if (reason === "deadline") f.order.payment.payment_deadline_at = "2020-01-01T00:00:00Z";
    if (reason === "credential") f.attempt.expires_at = "2020-01-01T00:00:00Z";
    if (reason === "missing-expiry") f.attempt.expires_at = null;
    if (reason === "inactive") f.order.payment.active_attempt_number = 2;
    if (reason === "wrong-transaction") f.order.payment.active_payment_transaction_id = "other";
    if (reason === "wrong-amount") f.attempt.amount.amount_minor = 1;
    if (reason === "cancelled") f.order.order_status = "CANCELLED";
    open(); expect(screen.queryByRole("button", { name: "Thanh toán an toàn" })).toBeNull();
  });
  it("does not confirm if cancellation won while the form was open", async () => {
    open(); f.readOrder.mockResolvedValue({ data: { ...f.order, order_status: "CANCELLED" }, isError: false });
    fireEvent.click(confirmButton());
    await screen.findByText(/Lượt thanh toán đã thay đổi hoặc hết hạn/);
    expect(f.readAttempt).toHaveBeenCalledOnce(); expect(f.confirm).not.toHaveBeenCalled();
  });
  it("does not send confirm when a fresh read fails, even with cached data", async () => {
    open(); f.readOrder.mockResolvedValue({ data: f.order, isError: true }); fireEvent.click(confirmButton());
    await screen.findByText(/yêu cầu thanh toán chưa được gửi/); expect(f.confirm).not.toHaveBeenCalled();
  });
  it("blocks concurrent submit calls before the asynchronous state update", async () => {
    open(); const form = confirmButton().closest("form")!; fireEvent.submit(form); fireEvent.submit(form);
    await waitFor(() => expect(f.confirm).toHaveBeenCalledOnce());
    expect(f.readOrder).toHaveBeenCalledOnce();
  });
});

describe("ambiguous provider confirmation", () => {
  it.each(["api_connection_error", "api_error", "reject"])("reads Order and locks confirm for %s, including remount", async (type) => {
    if (type === "reject") f.confirm.mockRejectedValue(new Error("response lost"));
    else f.confirm.mockResolvedValue({ error: { type } });
    const view = open(); fireEvent.click(confirmButton());
    await waitFor(() => expect(f.readOrder).toHaveBeenCalledTimes(2));
    // A refetch invocation precedes the async finally/render that clears the busy label.
    expect((await screen.findByRole("button", { name: "Thanh toán an toàn" })).hasAttribute("disabled")).toBe(true);
    expect(sessionStorage.getItem(confirmationKey("1", 1))).toBe("1");
    view.unmount(); open(); expect(confirmButton().hasAttribute("disabled")).toBe(true);
    fireEvent.click(screen.getByRole("button", { name: "Kiểm tra kết quả thanh toán" }));
    await waitFor(() => expect(f.readOrder).toHaveBeenCalledTimes(3));
    expect((await screen.findByRole("button", { name: "Thanh toán an toàn" })).hasAttribute("disabled")).toBe(true); expect(f.confirm).toHaveBeenCalledOnce();
  });
  it("keeps the unknown marker when recovery reads fail, then resolves to PAID", async () => {
    f.confirm.mockResolvedValue({ error: { type: "api_connection_error" } });
    f.readOrder.mockResolvedValueOnce({ data: f.order, isError: false }).mockRejectedValue(new Error("Order unavailable"));
    open(); fireEvent.click(confirmButton());
    await screen.findByText(/Chưa đọc được kết quả thanh toán/);
    expect(confirmButton().hasAttribute("disabled")).toBe(true);
    f.readOrder.mockResolvedValue({ data: { ...f.order, order_status: "PROCESSING", payment_status: "PAID" }, isError: false });
    fireEvent.click(screen.getByRole("button", { name: "Kiểm tra kết quả thanh toán" }));
    await waitFor(() => expect(f.push).toHaveBeenCalledWith("/orders/1/payment/complete?attempt=1"));
    expect(sessionStorage.getItem(confirmationKey("1", 1))).toBeNull();
  });
  it("resolves a FAILED attempt through Order instead of confirming it again", async () => {
    markConfirmationUnknown(confirmationKey("1", 1), true); open();
    f.readAttempt.mockResolvedValue({ data: { ...f.attempt, status: "FAILED", client_secret: null }, isError: false });
    fireEvent.click(screen.getByRole("button", { name: "Kiểm tra kết quả thanh toán" }));
    await waitFor(() => expect(f.push).toHaveBeenCalledWith("/orders/1")); expect(f.confirm).not.toHaveBeenCalled();
  });
  it("allows another card on the same intent after a definite card decline", async () => {
    f.confirm.mockResolvedValueOnce({ error: { type: "card_error", code: "card_declined" } });
    open(); fireEvent.click(confirmButton()); await screen.findByText(/Thẻ đã bị từ chối/);
    expect(confirmButton().hasAttribute("disabled")).toBe(false);
    fireEvent.click(confirmButton()); await waitFor(() => expect(f.confirm).toHaveBeenCalledTimes(2));
    expect(f.push).toHaveBeenCalledWith("/orders/1/payment/complete?attempt=1");
  });
  it("allows retry after a definitive 3DS cancellation reported as invalid_request_error", async () => {
    f.confirm.mockResolvedValueOnce({ error: { type: "invalid_request_error", code: "payment_intent_authentication_failure" } });
    open(); fireEvent.click(confirmButton()); await screen.findByText(/Xác thực thanh toán chưa hoàn tất/);
    expect(confirmButton().hasAttribute("disabled")).toBe(false);
    expect(sessionStorage.getItem(confirmationKey("1", 1))).toBeNull();
    fireEvent.click(confirmButton()); await waitFor(() => expect(f.confirm).toHaveBeenCalledTimes(2));
    expect(f.push).toHaveBeenCalledWith("/orders/1/payment/complete?attempt=1");
  });
});
