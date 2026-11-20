import { isRetryable, problemStatus } from "@/lib/api/problem";
/** A failed response may arrive after commit; never resend irreversible commands blindly. */
export function isCommandOutcomeUnknown(error: unknown) {
  const status = problemStatus(error);
  return (
    (status !== null && status >= 500) ||
    (status === null && isRetryable(error))
  );
}
