import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/notification";

type Schemas = components["schemas"];

export type InboxItem = Schemas["InboxItem"];
export type InboxPage = Schemas["InboxPage"];
export type UnreadCount = Schemas["UnreadCount"];
export interface InboxListParams {
  cursor?: string;
  limit?: number;
  unread_only?: boolean;
}

const INBOX_BASE = "/api/v1/notifications/inbox";

export const notificationApi = {
  list: async (params: InboxListParams, signal?: AbortSignal) =>
    (await api.get<InboxPage>(INBOX_BASE, { params, signal })).data,

  /** Đọc từ Redis, có thể cũ vài giây: chỉ dùng cho huy hiệu, không dùng để suy ra danh sách. */
  unreadCount: async (signal?: AbortSignal) =>
    (await api.get<UnreadCount>(`${INBOX_BASE}/unread-count`, { signal })).data,

  /** Idempotent tự nhiên theo contract nên không cần Idempotency-Key. */
  setRead: async (itemId: string, read: boolean) =>
    (await api.patch<InboxItem>(`${INBOX_BASE}/${encodeURIComponent(itemId)}`, { read })).data,

  archive: async (itemId: string): Promise<void> => {
    await api.delete(`${INBOX_BASE}/${encodeURIComponent(itemId)}`);
  },

  markAllRead: async (): Promise<void> => {
    await api.post(`${INBOX_BASE}/mark-all-read`);
  },
};
