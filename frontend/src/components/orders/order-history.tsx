import type { OrderDetail } from "@/lib/api/order";
import { formatDateTime } from "@/lib/format";
import { RETURN_REASONS } from "@/lib/labels/order";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";

const CANCEL_REASONS: Record<string, string> = {
  USER_CANCEL: "Bạn đã yêu cầu hủy đơn",
  ADMIN_FORCE_CANCEL: "Cửa hàng đã hủy đơn",
  PAYMENT_TIMEOUT: "Đơn hàng đã hết hạn thanh toán",
  PAYMENT_FAILED: "Thanh toán không thành công",
  PRE_PAYMENT_SAGA_FAILED: "Không thể hoàn tất bước chuẩn bị đơn hàng",
};

/** Show only the lifecycle milestones supplied by Order, including external remediation. */
export function OrderHistory({ order }: { order: OrderDetail }) {
  const milestones = [
    { label: "Đã đặt hàng", at: order.created_at },
    { label: "Đã thanh toán", at: order.paid_at },
    { label: "Đã hoàn thành đơn", at: order.completed_at },
    { label: "Đã hủy đơn", at: order.cancelled_at },
    { label: "Hoàn tất xử lý hoàn tiền", at: order.refunded_at },
  ].filter((item): item is { label: string; at: string } => Boolean(item.at))
    .sort((a, b) => Date.parse(a.at) - Date.parse(b.at));
  return <Card>
    <CardHeader><CardTitle>Thông tin xử lý đơn hàng</CardTitle></CardHeader>
    <CardContent className="space-y-5">
      {order.cancel_reason && <p className="text-[14px]"><span className="font-medium">Lý do hủy: </span>{CANCEL_REASONS[order.cancel_reason] ?? "Đơn hàng được hủy trong quá trình xử lý"}</p>}
      {order.return_reason && <p className="text-[14px]"><span className="font-medium">Lý do trả hàng: </span>{RETURN_REASONS.find(reason => reason.value === order.return_reason)?.label ?? order.return_reason}</p>}
      <ol className="space-y-3" aria-label="Các mốc xử lý đơn hàng">{milestones.map(item => <li key={item.label} className="flex flex-wrap justify-between gap-2 text-[14px]">
        <span>{item.label}</span><time dateTime={item.at} className="tabular-nums text-muted-foreground">{formatDateTime(item.at)}</time>
      </li>)}</ol>
    </CardContent>
  </Card>;
}
