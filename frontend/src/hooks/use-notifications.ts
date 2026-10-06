"use client";

import { useMutation, useQuery, useQueryClient, type InfiniteData, type QueryKey } from "@tanstack/react-query";

import { useCursorInfiniteList } from "@/hooks/use-cursor-list";
import { notificationApi, type InboxItem, type InboxPage } from "@/lib/api/notification";
import { useAuthStore } from "@/store/useAuthStore";

/** Chu kỳ poll huy hiệu: contract Notification ghi 30–60 giây, không có realtime ở phase 1. */
export const UNREAD_POLL_MS = 45_000;

export const notificationKeys = {
  all: ["notifications"] as const,
  lists: (accountId: string | undefined) => ["notifications", accountId, "list"] as const,
  list: (accountId: string | undefined, unreadOnly: boolean) =>
    ["notifications", accountId, "list", { unreadOnly }] as const,
  unread: (accountId: string | undefined) => ["notifications", accountId, "unread-count"] as const,
};

function useAccount() {
  const status = useAuthStore((state) => state.status);
  const accountId = useAuthStore((state) => state.claims?.sub);
  return { accountId, ready: status === "authenticated" && Boolean(accountId) };
}

/**
 * Số chưa đọc cho huy hiệu trên header. Giá trị đến từ Redis nên có thể lệch vài
 * giây so với danh sách; nó chỉ là gợi ý, không dùng để suy ra nội dung hộp thư.
 * Không poll khi tab ẩn; lỗi không làm hỏng header (huy hiệu đơn giản biến mất).
 */
export function useUnreadCount() {
  const { accountId, ready } = useAccount();
  return useQuery({
    queryKey: notificationKeys.unread(accountId),
    queryFn: ({ signal }) => notificationApi.unreadCount(signal),
    enabled: ready,
    staleTime: 30_000,
    refetchInterval: UNREAD_POLL_MS,
    refetchIntervalInBackground: false,
    refetchOnWindowFocus: true,
    retry: 1,
  });
}

export function useInbox(unreadOnly: boolean) {
  const { accountId, ready } = useAccount();
  return useCursorInfiniteList({
    queryKey: notificationKeys.list(accountId, unreadOnly),
    fetchPage: (cursor, signal) =>
      notificationApi.list({ limit: 20, cursor, ...(unreadOnly ? { unread_only: true } : {}) }, signal),
    enabled: ready,
    staleTime: 10_000,
    refetchOnMount: true,
    refetchOnWindowFocus: true,
  });
}

type InboxCache = InfiniteData<InboxPage, string | undefined>;

function mapPages(data: InboxCache | undefined, map: (items: InboxItem[]) => InboxItem[]): InboxCache | undefined {
  return data && { ...data, pages: data.pages.map((page) => ({ ...page, items: map(page.items) })) };
}

/** Thao tác trên một thông báo và trên cả hộp thư. */
export function useInboxActions() {
  const client = useQueryClient();
  const accountId = useAuthStore((state) => state.claims?.sub);
  const lists = notificationKeys.lists(accountId) as QueryKey;

  const refreshCount = () => client.invalidateQueries({ queryKey: notificationKeys.unread(accountId) });

  const setRead = useMutation({
    mutationFn: ({ id, read }: { id: string; read: boolean }) => notificationApi.setRead(id, read),
    onSuccess: (updated) => {
      // Cập nhật tại chỗ bằng bản server trả về; tab "Chưa đọc" sẽ gọn lại ở lần tải kế tiếp.
      client.setQueriesData<InboxCache>({ queryKey: lists }, (data) =>
        mapPages(data, (items) => items.map((item) => (item.id === updated.id ? updated : item)))
      );
    },
    onSettled: refreshCount,
  });

  const archive = useMutation({
    mutationFn: (id: string) => notificationApi.archive(id),
    onSuccess: (_void, id) => {
      client.setQueriesData<InboxCache>({ queryKey: lists }, (data) =>
        mapPages(data, (items) => items.filter((item) => item.id !== id))
      );
    },
    onSettled: refreshCount,
  });

  const markAllRead = useMutation({
    mutationFn: () => notificationApi.markAllRead(),
    onSuccess: () => {
      const now = new Date().toISOString();
      client.setQueriesData<InboxCache>({ queryKey: lists }, (data) =>
        mapPages(data, (items) => items.map((item) => (item.read ? item : { ...item, read: true, read_at: now })))
      );
      client.setQueryData(notificationKeys.unread(accountId), { count: 0, as_of: now });
    },
    onSettled: async () => {
      await Promise.all([refreshCount(), client.invalidateQueries({ queryKey: lists })]);
    },
  });

  return { setRead, archive, markAllRead };
}
