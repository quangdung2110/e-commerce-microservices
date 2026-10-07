import { api } from "@/lib/api/client";
import { idempotencyHeaders } from "@/lib/api/command";
import type { components } from "@/lib/api/generated/cart";

export type Cart = components["schemas"]["Cart"];
export type CartItem = components["schemas"]["CartItem"];
export type CartMoney = components["schemas"]["Money"];
export type CartAdjustment = components["schemas"]["AdjustItemRequest"];
export type CartMutation = components["schemas"]["CartMutationResult"];
export type CartDeletion = components["schemas"]["DeleteItemsRequest"];
export type CartDeletionResult = components["schemas"]["DeleteItemsResult"];

const CART_BASE = "/api/v1/carts/mine";

/** Cart v1: quantity changes are deltas; single and batch removal share DELETE. */
export const cartApi = {
  mine: async () => (await api.get<Cart>(CART_BASE)).data,
  adjust: async (body: CartAdjustment, commandId: string) =>
    (await api.post<CartMutation>(`${CART_BASE}/items`, body, {
      headers: idempotencyHeaders(commandId),
    })).data,
  remove: async (body: CartDeletion, commandId: string) =>
    (await api.delete<CartDeletionResult>(`${CART_BASE}/items`, {
      data: body,
      headers: idempotencyHeaders(commandId),
    })).data,
};
