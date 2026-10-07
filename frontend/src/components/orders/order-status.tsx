import { StatusBadge } from "@/components/admin/status-badge";
import type { OrderCard, OrderDetail, OrderOperation, RefundCase } from "@/lib/api/order";
import {
  FULFILLMENT_STATUS_LABELS,
  OPERATION_STATUS_LABELS,
  ORDER_STATUS_LABELS,
  ORDER_UI_STATUS_LABELS,
  PAYMENT_STATUS_LABELS,
  REFUND_STATUS_LABELS,
} from "@/lib/labels/order";

export function OrderCardStatus({ status }: { status: OrderCard["ui_primary_status"] }) {
  return <StatusBadge value={status} labels={ORDER_UI_STATUS_LABELS} />;
}

export function OrderStatus({ status }: { status: OrderDetail["order_status"] }) {
  return <StatusBadge value={status} labels={ORDER_STATUS_LABELS} />;
}

export function PaymentStatus({ status }: { status: OrderDetail["payment_status"] }) {
  return <StatusBadge value={status} labels={PAYMENT_STATUS_LABELS} />;
}

export function FulfillmentStatus({ status }: { status: OrderDetail["items"][number]["lines"][number]["fulfillment_status"] }) {
  return <StatusBadge value={status} labels={FULFILLMENT_STATUS_LABELS} />;
}

export function RefundStatus({ status }: { status: RefundCase["status"] }) {
  return <StatusBadge value={status} labels={REFUND_STATUS_LABELS} />;
}

export function OperationStatus({ status }: { status: OrderOperation["status"] }) {
  return <StatusBadge value={status} labels={OPERATION_STATUS_LABELS} />;
}
