import { api } from "@/lib/api/client";
import { idempotencyHeaders } from "@/lib/api/command";
import type { components, operations } from "@/lib/api/generated/order";

type Schemas = components["schemas"];

export type BuyerTab = Schemas["BuyerTab"];
export type OrderStatus = Schemas["OrderStatus"];
export type PaymentStatus = Schemas["PaymentStatus"];
export type OrderCard = Schemas["OrderCard"];
export type OrderDetail = Schemas["OrderDetail"];
export type OrderItem = Schemas["OrderItem"];
export type OrderLine = Schemas["OrderLine"];
export type OrderMoney = Schemas["Money"];
export type OrderSearchPage = Schemas["OrderSearchPage"];
export type OrderOperation = Schemas["OrderOperation"];
export type OperationAccepted = Schemas["OperationAccepted"];
export type ReturnOperationAccepted = Schemas["ReturnOperationAccepted"];
export type BuyerReturnRequest = Schemas["BuyerReturnRequest"];
export type AdminPreDispatchRefundRequest = Schemas["AdminPreDispatchRefundRequest"];
export type DeliveryConfirmationRequest = Schemas["DeliveryConfirmationRequest"];
export type DeliveryConfirmationResult = Schemas["DeliveryConfirmationResult"];
export type PaymentAttempt = Schemas["PaymentAttempt"];
export type RefundCase = Schemas["RefundCase"];
export type OrderListParams = NonNullable<operations["listMyOrders"]["parameters"]["query"]>;
export type AdminOrderListParams = NonNullable<operations["listOrdersAsAdmin"]["parameters"]["query"]>;

const ORDER_BASE = "/api/v1/orders";
const ADMIN_ORDER_BASE = "/api/v1/admin/orders";

export const orderApi = {
  listMine: async (params: OrderListParams, signal?: AbortSignal) =>
    (await api.get<OrderSearchPage>(ORDER_BASE, { params, signal })).data,

  mine: async (orderId: string, signal?: AbortSignal) =>
    (await api.get<OrderDetail>(`${ORDER_BASE}/${encodeURIComponent(orderId)}`, { signal })).data,

  refundCase: async (orderId: string, signal?: AbortSignal) =>
    (await api.get<RefundCase>(`${ORDER_BASE}/${encodeURIComponent(orderId)}/refund-case`, { signal })).data,

  paymentAttempt: async (orderId: string, attemptNumber: number, signal?: AbortSignal) =>
    (
      await api.get<PaymentAttempt>(
        `${ORDER_BASE}/${encodeURIComponent(orderId)}/payment-attempts/${attemptNumber}`,
        { signal }
      )
    ).data,

  operation: async (operationId: string, signal?: AbortSignal) =>
    (await api.get<OrderOperation>(`/api/v1/order-operations/${encodeURIComponent(operationId)}`, { signal })).data,

  cancel: async (orderId: string, commandId: string) =>
    (
      await api.post<OperationAccepted>(
        `${ORDER_BASE}/${encodeURIComponent(orderId)}/cancellation-requests`,
        { reason_code: "USER_CANCEL" },
        { headers: idempotencyHeaders(commandId) }
      )
    ).data,

  requestReturn: async (orderId: string, body: BuyerReturnRequest, commandId: string) =>
    (
      await api.post<ReturnOperationAccepted>(
        `${ORDER_BASE}/${encodeURIComponent(orderId)}/return-requests`,
        body,
        { headers: idempotencyHeaders(commandId) }
      )
    ).data,

  retryPayment: async (orderId: string, returnUrl: string, commandId: string) =>
    (
      await api.post<OperationAccepted>(
        `${ORDER_BASE}/${encodeURIComponent(orderId)}/payment-attempts`,
        { return_url: returnUrl },
        { headers: idempotencyHeaders(commandId) }
      )
    ).data,

  listAdmin: async (params: AdminOrderListParams, signal?: AbortSignal) =>
    (await api.get<OrderSearchPage>(ADMIN_ORDER_BASE, { params, signal })).data,

  admin: async (orderId: string, signal?: AbortSignal) =>
    (
      await api.get<OrderDetail>(`${ADMIN_ORDER_BASE}/${encodeURIComponent(orderId)}`, {
        signal,
      })
    ).data,

  adminOperation: async (operationId: string, signal?: AbortSignal) =>
    (
      await api.get<OrderOperation>(
        `/api/v1/admin/order-operations/${encodeURIComponent(operationId)}`,
        { signal }
      )
    ).data,

  cancelAsAdmin: async (orderId: string, commandId: string) =>
    (
      await api.post<OperationAccepted>(
        `${ADMIN_ORDER_BASE}/${encodeURIComponent(orderId)}/cancellation-requests`,
        { reason_code: "ADMIN_FORCE_CANCEL" },
        { headers: idempotencyHeaders(commandId) }
      )
    ).data,

  confirmDelivery: async (orderId: string, body: DeliveryConfirmationRequest) =>
    (
      await api.post<DeliveryConfirmationResult>(
        `${ADMIN_ORDER_BASE}/${encodeURIComponent(orderId)}/delivery-confirmations`,
        body
      )
    ).data,

  requestPreDispatchRefund: async (
    orderId: string,
    body: AdminPreDispatchRefundRequest,
    commandId: string
  ) =>
    (
      await api.post<OperationAccepted>(
        `${ADMIN_ORDER_BASE}/${encodeURIComponent(orderId)}/pre-dispatch-refund-requests`,
        body,
        { headers: idempotencyHeaders(commandId) }
      )
    ).data,
};
