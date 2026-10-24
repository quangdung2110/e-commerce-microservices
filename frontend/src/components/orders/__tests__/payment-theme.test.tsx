// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render } from "@testing-library/react";
import type { StripeElementsOptions } from "@stripe/stripe-js";
import { PaymentContent } from "@/components/orders/payment-content";

const state = vi.hoisted(() => ({ theme: "dark", options: {} as StripeElementsOptions }));
vi.mock("next-themes", () => ({ useTheme: () => ({ resolvedTheme: state.theme }) }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock("@stripe/stripe-js", () => ({ loadStripe: () => Promise.resolve({}) }));
vi.mock("@stripe/react-stripe-js", () => ({
  Elements: ({ options, children }: { options: StripeElementsOptions; children: React.ReactNode }) => { state.options = options; return children; },
  PaymentElement: () => <div>Stripe fixture</div>, useStripe: () => null, useElements: () => null,
}));
vi.mock("@/hooks/use-orders", () => ({
  useOrder: () => ({ data: { order_id: "1", order_status: "PENDING_PAYMENT", payment_status: "PENDING", final_amount: { amount_minor: 1000, currency: "VND" }, payment: { active_attempt_number: 1, active_payment_transaction_id: "txn_fixture", payment_deadline_at: "2030-01-01T00:00:00Z" } } }),
  usePaymentAttempt: () => ({ data: { order_id: "1", status: "PENDING", client_secret: "test-secret", publishable_key: "pk_test_fixture", attempt_number: 1, payment_transaction_id: "txn_fixture", expires_at: "2030-01-01T00:00:00Z", amount: { amount_minor: 1000, currency: "VND" } } }),
}));
afterEach(cleanup);
describe("Stripe appearance follows site theme", () => {
  it("provides legible dark fields and updates to light without replacing the payment intent", () => {
    state.theme = "dark";
    const screen = render(<PaymentContent orderId="1" requestedAttempt={1} />);
    expect(state.options.appearance?.theme).toBe("night");
    expect(state.options.appearance?.variables?.colorText).toBe("#faf9f5");
    expect(state.options.appearance?.variables?.colorBackground).toBe("#1f1e1d");
    const secret = "clientSecret" in state.options ? state.options.clientSecret : undefined;
    state.theme = "light"; screen.rerender(<PaymentContent orderId="1" requestedAttempt={1} />);
    expect(state.options.appearance?.theme).toBe("stripe");
    expect(state.options.appearance?.variables?.colorText).toBe("#141413");
    expect(state.options.appearance?.variables?.colorBackground).toBe("#ffffff");
    expect("clientSecret" in state.options && state.options.clientSecret).toBe(secret);
  });
});
