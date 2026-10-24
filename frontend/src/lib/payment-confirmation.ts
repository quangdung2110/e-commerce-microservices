import type { OrderDetail, PaymentAttempt } from "@/lib/api/order";

/** Both the credential TTL and the immutable Order deadline constrain confirm. */
export function paymentExpiresAt(order: OrderDetail, attempt: PaymentAttempt): number | null {
  const deadline = Date.parse(order.payment.payment_deadline_at);
  const credentialExpiry = attempt.expires_at ? Date.parse(attempt.expires_at) : NaN;
  return Number.isFinite(deadline) && Number.isFinite(credentialExpiry)
    ? Math.min(deadline, credentialExpiry)
    : null;
}

export function canConfirmPayment(order: OrderDetail, attempt: PaymentAttempt, now: number): boolean {
  const expiresAt = paymentExpiresAt(order, attempt);
  return order.order_status === "PENDING_PAYMENT"
    && order.payment_status === "PENDING"
    && attempt.order_id === order.order_id
    && attempt.attempt_number === order.payment.active_attempt_number
    && attempt.payment_transaction_id === order.payment.active_payment_transaction_id
    && attempt.amount.amount_minor === order.final_amount.amount_minor
    && attempt.amount.currency === order.final_amount.currency
    && attempt.status === "PENDING"
    && Boolean(attempt.client_secret && attempt.publishable_key)
    && expiresAt !== null && now < expiresAt;
}

// No card data or Stripe credentials are persisted. This marker prevents a
// response lost after sending confirm from becoming another confirm on reload.
const memoryMarkers = new Set<string>();
const changeEvent = "payment-confirmation-changed";
export const confirmationKey = (orderId: string, attemptNumber: number) =>
  `payment-confirm-unknown:${orderId}:${attemptNumber}`;

export function confirmationUnknown(key: string): boolean {
  try { return memoryMarkers.has(key) || sessionStorage.getItem(key) === "1"; }
  catch { return memoryMarkers.has(key); }
}

export function markConfirmationUnknown(key: string, unknown: boolean): void {
  if (unknown) memoryMarkers.add(key); else memoryMarkers.delete(key);
  try {
    if (unknown) sessionStorage.setItem(key, "1"); else sessionStorage.removeItem(key);
  } catch { /* Retain the in-memory guard if browser storage is unavailable. */ }
  window.dispatchEvent(new Event(changeEvent));
}

export function subscribeConfirmation(onChange: () => void): () => void {
  window.addEventListener(changeEvent, onChange);
  window.addEventListener("storage", onChange);
  return () => {
    window.removeEventListener(changeEvent, onChange);
    window.removeEventListener("storage", onChange);
  };
}
