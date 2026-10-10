import { api } from "@/lib/api/client";
import { idempotencyHeaders } from "@/lib/api/command";
import { toPollRead } from "@/lib/api/operation";
import type { components } from "@/lib/api/generated/checkout";

type Schemas = components["schemas"];
export type CheckoutPreview = Schemas["CheckoutPreview"];
export type CheckoutPreviewRequest = Schemas["CheckoutPreviewRequest"];
export type CheckoutProblem = Schemas["CheckoutProblem"];
export type VoucherOption = Schemas["VoucherOption"];
export type VoucherOptions = Schemas["VoucherOptions"];
export type PromotionReason = Schemas["PromotionReason"];
export type SubmissionReady = Schemas["CheckoutSubmissionReady"];
export type SubmissionPending = Schemas["CheckoutSubmissionPending"];
export type CheckoutSubmission = SubmissionReady | SubmissionPending;

const BASE = "/api/v1/checkout";

/** Submit is idempotent by checkout_token, NOT by a new command UUID. */
export const checkoutApi = {
  voucherOptions: async (token: string, signal?: AbortSignal): Promise<VoucherOptions> =>
    (await api.get<VoucherOptions>(`${BASE}/previews/${encodeURIComponent(token)}/voucher-options`, {
      signal, timeout: 8_000,
    })).data,
  preview: async (body: CheckoutPreviewRequest, commandId: string, signal?: AbortSignal) =>
    (await api.post<CheckoutPreview>(`${BASE}/preview`, body, {
      headers: idempotencyHeaders(commandId), signal, timeout: 8_000,
    })).data,
  submit: async (token: string) => toPollRead(await api.post<CheckoutSubmission>(
    `${BASE}/submit`, { checkout_token: token }, { timeout: 25_000 },
  )),
  submission: async (token: string, signal?: AbortSignal) => toPollRead(await api.get<CheckoutSubmission>(
    `${BASE}/submissions/${encodeURIComponent(token)}`, { signal, timeout: 25_000 },
  )),
};
