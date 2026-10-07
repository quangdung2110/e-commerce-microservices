"use client";

import { useEffect, useRef } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";

import { useCommand } from "@/hooks/use-command";
import { useCursorInfiniteList } from "@/hooks/use-cursor-list";
import {
  orderApi,
  type BuyerReturnRequest,
  type BuyerTab,
  type OperationAccepted,
  type OrderListParams,
  type OrderOperation,
} from "@/lib/api/order";
import { isTerminalOrderOperation } from "@/lib/order";
import { PollTimeoutError, pollUntil } from "@/lib/api/operation";
import { useAuthStore } from "@/store/useAuthStore";

export const orderKeys = {
  all: ["orders"] as const,
  lists: (accountId: string | undefined) => ["orders", accountId, "list"] as const,
  list: (accountId: string | undefined, tab: BuyerTab | undefined, q: string) =>
    ["orders", accountId, "list", { tab, q }] as const,
  detail: (accountId: string | undefined, orderId: string) =>
    ["orders", accountId, "detail", orderId] as const,
  refund: (accountId: string | undefined, orderId: string) =>
    ["orders", accountId, "refund", orderId] as const,
  attempt: (accountId: string | undefined, orderId: string, attempt: number | null) =>
    ["orders", accountId, "payment-attempt", orderId, attempt] as const,
};

export function useMyOrders(tab: BuyerTab | undefined, q: string) {
  const status = useAuthStore((state) => state.status);
  const accountId = useAuthStore((state) => state.claims?.sub);
  return useCursorInfiniteList({
    queryKey: orderKeys.list(accountId, tab, q),
    fetchPage: (cursor, signal) => {
      const params: OrderListParams = { limit: 20 };
      if (tab) params.tab = tab;
      if (q) params.q = q;
      if (cursor) params.cursor = cursor;
      return orderApi.listMine(params, signal);
    },
    enabled: status === "authenticated" && Boolean(accountId),
    staleTime: 15_000,
    refetchOnMount: true,
    refetchOnWindowFocus: true,
  });
}

export function useOrder(orderId: string, options?: { refetchInterval?: number | false; pollPayment?: boolean }) {
  const status = useAuthStore((state) => state.status);
  const accountId = useAuthStore((state) => state.claims?.sub);
  return useQuery({
    queryKey: orderKeys.detail(accountId, orderId),
    queryFn: ({ signal }) => orderApi.mine(orderId, signal),
    enabled: status === "authenticated" && Boolean(accountId && orderId),
    staleTime: 10_000,
    refetchInterval: options?.pollPayment
      ? (query) => query.state.data?.payment_status === "PENDING" ? 2_500 : false
      : options?.refetchInterval,
  });
}

export function useRefundCase(orderId: string, enabled: boolean) {
  const accountId = useAuthStore((state) => state.claims?.sub);
  return useQuery({
    queryKey: orderKeys.refund(accountId, orderId),
    queryFn: ({ signal }) => orderApi.refundCase(orderId, signal),
    enabled: Boolean(accountId && orderId && enabled),
    staleTime: 10_000,
    refetchInterval: (query) => {
      const status = query.state.data?.status;
      return status && ["SUCCEEDED", "FAILED", "EXTERNALLY_REMEDIATED", "CLOSED_INCOMPLETE"].includes(status)
        ? false
        : 5_000;
    },
  });
}

export function usePaymentAttempt(orderId: string, attemptNumber: number | null) {
  const accountId = useAuthStore((state) => state.claims?.sub);
  return useQuery({
    queryKey: orderKeys.attempt(accountId, orderId, attemptNumber),
    queryFn: ({ signal }) => orderApi.paymentAttempt(orderId, attemptNumber!, signal),
    enabled: Boolean(accountId && orderId && attemptNumber),
    staleTime: 0,
    refetchOnMount: "always",
  });
}

export type OrderCommandIntent =
  | { kind: "cancel" }
  | { kind: "return"; body: BuyerReturnRequest }
  | { kind: "retry-payment"; returnUrl: string };

export interface OrderCommandResult {
  accepted: OperationAccepted;
  operation: OrderOperation | null;
  stillProcessing: boolean;
}

/**
 * Buyer command + bounded operation polling. A timeout means the durable command
 * is still running, never that the user should create another intent.
 */
export function useOrderCommand(orderId: string) {
  const client = useQueryClient();
  const accountId = useAuthStore((state) => state.claims?.sub);
  const controller = useRef<AbortController | null>(null);

  useEffect(() => () => controller.current?.abort(), []);

  return useCommand<OrderCommandResult, OrderCommandIntent>({
    mutationFn: async (intent, { commandId }) => {
      const accepted =
        intent.kind === "cancel"
          ? await orderApi.cancel(orderId, commandId)
          : intent.kind === "return"
            ? await orderApi.requestReturn(orderId, intent.body, commandId)
            : await orderApi.retryPayment(orderId, intent.returnUrl, commandId);

      controller.current?.abort();
      controller.current = new AbortController();

      try {
        const operation = await pollUntil({
          signal: controller.current.signal,
          timeoutMs: 45_000,
          initialDelayMs: 1_000,
          maxDelayMs: 4_000,
          read: async (signal) => ({
            data: await orderApi.operation(accepted.operation_id, signal),
            retryAfterSeconds: null,
          }),
          isDone: isTerminalOrderOperation,
        });
        return { accepted, operation, stillProcessing: false };
      } catch (error) {
        if (error instanceof PollTimeoutError) {
          return { accepted, operation: error.lastData ?? null, stillProcessing: true };
        }
        throw error;
      }
    },
    onSettled: async () => {
      await Promise.all([
        client.invalidateQueries({ queryKey: orderKeys.detail(accountId, orderId) }),
        client.invalidateQueries({ queryKey: orderKeys.lists(accountId) }),
        client.invalidateQueries({ queryKey: orderKeys.refund(accountId, orderId) }),
      ]);
    },
  });
}
