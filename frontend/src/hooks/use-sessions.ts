"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";

import { useCursorInfiniteList } from "@/hooks/use-cursor-list";
import { authApi } from "@/lib/api/auth";
import { useAuthStore } from "@/store/useAuthStore";

const SESSIONS_KEY = ["auth", "sessions"] as const;

/** Các phiên đăng nhập đang hiệu lực của tài khoản, phân trang cursor. */
export function useSessions() {
  const enabled = useAuthStore((state) => state.status) === "authenticated";
  return useCursorInfiniteList({
    queryKey: SESSIONS_KEY,
    fetchPage: (cursor, signal) => authApi.listSessions(cursor, signal),
    enabled,
  });
}

/**
 * Thu hồi một phiên. Thu hồi phiên của chính thiết bị này tương đương đăng xuất;
 * `authApi` lo phần kết thúc phiên local.
 */
export function useRevokeSession() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (sessionId: string) => authApi.revokeSession(sessionId),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: SESSIONS_KEY }),
  });
}

/** Thu hồi mọi phiên, kể cả thiết bị này. */
export function useRevokeAllSessions() {
  return useMutation({ mutationFn: () => authApi.revokeAllSessions() });
}
