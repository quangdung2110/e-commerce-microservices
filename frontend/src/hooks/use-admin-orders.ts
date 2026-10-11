"use client";

import { useEffect, useRef } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { useCommand } from "@/hooks/use-command";
import { useCursorPager } from "@/hooks/use-cursor-list";
import {
  orderApi,
  type AdminOrderListParams,
  type AdminPreDispatchRefundRequest,
  type DeliveryConfirmationRequest,
  type OperationAccepted,
  type OrderOperation,
} from "@/lib/api/order";
import { PollTimeoutError, pollUntil } from "@/lib/api/operation";
import { isTerminalOrderOperation } from "@/lib/order";

export const adminOrderKeys = {
  all: ["admin", "orders"] as const,
  lists: () => ["admin", "orders", "list"] as const,
  list: (filters: AdminOrderListParams) => ["admin", "orders", "list", filters] as const,
  detail: (orderId: string) => ["admin", "orders", "detail", orderId] as const,
};

export function useAdminOrders(filters: AdminOrderListParams) {
  return useCursorPager({
    queryKey: adminOrderKeys.list(filters),
    fetchPage: (cursor, signal) =>
      orderApi.listAdmin({ ...filters, cursor, limit: 20 }, signal),
  });
}

export function useAdminOrder(orderId: string) {
  return useQuery({
    queryKey: adminOrderKeys.detail(orderId),
    queryFn: ({ signal }) => orderApi.admin(orderId, signal),
    enabled: Boolean(orderId),
    staleTime: 10_000,
  });
}

export type AdminOrderCommandIntent =
  | { kind: "cancel" }
  | { kind: "refund"; body: AdminPreDispatchRefundRequest };

export interface AdminOrderCommandResult {
  accepted: OperationAccepted;
  operation: OrderOperation | null;
  stillProcessing: boolean;
}

export function useAdminOrderCommand(orderId: string) {
  const queryClient = useQueryClient();
  const controller = useRef<AbortController | null>(null);

  useEffect(() => () => controller.current?.abort(), []);

  return useCommand<AdminOrderCommandResult, AdminOrderCommandIntent>({
    mutationFn: async (intent, { commandId }) => {
      const accepted =
        intent.kind === "cancel"
          ? await orderApi.cancelAsAdmin(orderId, commandId)
          : await orderApi.requestPreDispatchRefund(orderId, intent.body, commandId);

      controller.current?.abort();
      controller.current = new AbortController();

      try {
        const operation = await pollUntil({
          signal: controller.current.signal,
          timeoutMs: 45_000,
          initialDelayMs: 1_000,
          maxDelayMs: 4_000,
          read: async (signal) => ({
            data: await orderApi.adminOperation(accepted.operation_id, signal),
            retryAfterSeconds: null,
          }),
          isDone: isTerminalOrderOperation,
        });
        return { accepted, operation, stillProcessing: false };
      } catch (error) {
        if (error instanceof PollTimeoutError) {
          return {
            accepted,
            operation: error.lastData ?? null,
            stillProcessing: true,
          };
        }
        throw error;
      }
    },
    onSettled: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: adminOrderKeys.detail(orderId) }),
        queryClient.invalidateQueries({ queryKey: adminOrderKeys.lists() }),
      ]);
    },
  });
}

/**
 * Admin xác nhận giao hàng (Order ADR-021). Lệnh đồng bộ và idempotent theo trạng thái dòng,
 * nên không cần `Idempotency-Key` hay polling operation: thử lại cùng nội dung luôn an toàn.
 */
export function useAdminDeliveryConfirmation(orderId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: DeliveryConfirmationRequest) => orderApi.confirmDelivery(orderId, body),
    onSettled: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: adminOrderKeys.detail(orderId) }),
        queryClient.invalidateQueries({ queryKey: adminOrderKeys.lists() }),
      ]);
    },
  });
}
