import type { CheckoutProblem, CheckoutSubmission, SubmissionReady } from "@/lib/api/checkout";
import { getProblem } from "@/lib/api/problem";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
export function checkoutToken(value: unknown): string | null {
  return typeof value === "string" && UUID.test(value) ? value : null;
}

/** Only accept the canonical relative status path; never send auth to a response URL. */
export function submissionTokenFromUrl(value: unknown): string | null {
  if (typeof value !== "string") return null;
  const match = /^\/api\/v1\/checkout\/submissions\/([^/?#]+)$/.exec(value);
  return match ? checkoutToken(match[1]) : null;
}

export function checkoutProblem(error: unknown): CheckoutProblem | null {
  const problem = getProblem(error);
  return problem && "action" in problem ? problem as CheckoutProblem : null;
}

export function isSubmissionReady(value: CheckoutSubmission): value is SubmissionReady {
  return "order_id" in value;
}

export function submissionDestination(value: SubmissionReady): string {
  const path = `/orders/${encodeURIComponent(value.order_id)}`;
  return value.payment.usable && value.payment.client_secret && value.payment.publishable_key
    ? `${path}/payment?attempt=${value.payment.payment_attempt_number}`
    : path;
}
