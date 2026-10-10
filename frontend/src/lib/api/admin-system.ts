import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/search";
import type { AccountDeletionAccepted } from "@/lib/api/auth";

export type ReindexRun = components["schemas"]["ReindexRun"];
export type ReindexStatus = components["schemas"]["ReindexRunStatus"];
const BASE = "/api/v2/search/reindex-runs";
export const adminSystemApi = {
  listRuns: async (status?: ReindexStatus, signal?: AbortSignal) =>
    (
      await api.get<components["schemas"]["ReindexRunList"]>(BASE, {
        params: { status, limit: 50 },
        signal,
      })
    ).data,
  getRun: async (id: string, signal?: AbortSignal) =>
    (await api.get<ReindexRun>(`${BASE}/${encodeURIComponent(id)}`, { signal }))
      .data,
  createRun: async (reason: string) =>
    (await api.post<ReindexRun>(BASE, { reason })).data,
  cancelRun: async (id: string) =>
    (
      await api.post<ReindexRun>(
        `${BASE}/${encodeURIComponent(id)}/cancellation`,
      )
    ).data,
  rollbackRun: async (id: string) =>
    (await api.post<ReindexRun>(`${BASE}/${encodeURIComponent(id)}/rollback`))
      .data,
  deleteAccount: async (id: string) =>
    (
      await api.post<AccountDeletionAccepted>(
        `/api/v1/auth/admin/accounts/${encodeURIComponent(id)}/deletion-requests`,
      )
    ).data,
};
