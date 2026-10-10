import { api } from "@/lib/api/client";
import type { components, operations } from "@/lib/api/generated/review";

type Schemas = components["schemas"];
export type ModerationCase = Schemas["AdminModerationCase"];
export type ModerationParams = NonNullable<
  operations["listReviewsUnderModeration"]["parameters"]["query"]
>;
const BASE = "/api/v1/admin/reviews";

/** No Idempotency-Key: dismissal is outcome-idempotent, reply is a PUT upsert. */
export const adminReviewApi = {
  list: async (params: ModerationParams, signal?: AbortSignal) =>
    (
      await api.get<Schemas["AdminModerationCasePage"]>(BASE, {
        params,
        signal,
      })
    ).data,
  dismiss: async (id: string) =>
    (
      await api.post<Schemas["AdminModerationResult"]>(
        `${BASE}/${encodeURIComponent(id)}/report-dismissals`,
      )
    ).data,
  reply: async (id: string, content: string) =>
    (
      await api.put<Schemas["OfficialReply"]>(
        `${BASE}/${encodeURIComponent(id)}/reply`,
        { content },
      )
    ).data,
  takedown: async (id: string, body: Schemas["CreateTakedownRequest"]) =>
    (
      await api.post<Schemas["TakedownReceipt"]>(
        `${BASE}/${encodeURIComponent(id)}/takedown`,
        body,
      )
    ).data,
};
