"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { FormEvent, useMemo, useState } from "react";
import { ArrowLeft, LoaderCircle, PackageCheck } from "lucide-react";

import { OrderLineCard } from "@/components/orders/order-line-card";
import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import { EmptyState } from "@/components/ui/empty-state";
import { ErrorState } from "@/components/ui/error-state";
import { NativeSelect } from "@/components/ui/native-select";
import { Skeleton } from "@/components/ui/skeleton";
import { Textarea } from "@/components/ui/textarea";
import { useToast } from "@/components/ui/toast";
import { useOrder, useOrderCommand } from "@/hooks/use-orders";
import { RETURN_REASONS } from "@/lib/labels/order";
import { isManualReviewOperation, operationFailureMessage, orderLines } from "@/lib/order";

export function ReturnRequestContent({ orderId }: { orderId: string }) {
  const router = useRouter();
  const { addToast } = useToast();
  const order = useOrder(orderId);
  const command = useOrderCommand(orderId);
  const [selected, setSelected] = useState<string[]>([]);
  const [reason, setReason] = useState(RETURN_REASONS[0].value as string);
  const [details, setDetails] = useState("");
  const [formError, setFormError] = useState<string | null>(null);
  const lines = useMemo(() => order.data ? orderLines(order.data) : [], [order.data]);

  function toggle(lineId: string, checked: boolean) {
    setSelected((current) => checked ? [...new Set([...current, lineId])] : current.filter((id) => id !== lineId));
    setFormError(null);
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (selected.length === 0) {
      setFormError("Hãy chọn ít nhất một sản phẩm cần trả.");
      return;
    }
    command.mutate(
      {
        kind: "return",
        body: {
          lines: lines.filter((line) => selected.includes(line.order_line_id)).map((line) => ({ order_line_id: line.order_line_id, quantity: line.quantity })),
          reason_code: reason,
          reason_details: details.trim() || null,
        },
      },
      {
        onSuccess: (result) => {
          if (result.stillProcessing) {
            addToast({ type: "info", title: "Yêu cầu trả hàng đang được xử lý", message: "Không cần gửi lại. Tiến trình sẽ xuất hiện trong chi tiết đơn." });
            router.push(`/orders/${orderId}`);
            return;
          }
          if (result.operation?.status === "SUCCEEDED") {
            addToast({ type: "success", title: "Đã gửi yêu cầu trả hàng" });
            router.push(`/orders/${orderId}`);
            return;
          }
          setFormError(operationFailureMessage(result.operation, "Yêu cầu trả hàng chưa thể hoàn tất."));
        },
      }
    );
  }

  if (order.isPending) {
    return <main className="mx-auto w-full max-w-small space-y-8 px-6 py-10 md:px-8 md:py-14" aria-busy="true"><Skeleton className="h-5 w-36" /><Skeleton className="h-10 w-72" /><Skeleton className="h-72 w-full rounded-xl" /></main>;
  }
  if (order.isError || !order.data) {
    return <main className="mx-auto w-full max-w-small space-y-6 px-6 py-10 md:px-8 md:py-14"><Back orderId={orderId} /><ErrorState error={order.error} onRetry={() => void order.refetch()} /></main>;
  }
  if (!order.data.available_actions.can_return) {
    return (
      <main className="mx-auto w-full max-w-small space-y-8 px-6 py-10 md:px-8 md:py-14">
        <Back orderId={orderId} />
        <EmptyState icon={PackageCheck} title="Đơn hàng không thể gửi yêu cầu trả" description="Đơn có thể chưa giao đủ, chưa hoàn thành hoặc đã dùng quyền yêu cầu trả hàng." action={<Link href={`/orders/${orderId}`} className="focus-ring rounded-lg text-[14px] font-medium text-clay-strong hover:underline">Xem trạng thái đơn</Link>} />
      </main>
    );
  }

  return (
    <main className="mx-auto w-full max-w-small space-y-8 px-6 py-10 md:px-8 md:py-14">
      <Back orderId={orderId} />
      <header className="space-y-2">
        <h1 className="font-serif text-headline-2 font-normal">Yêu cầu trả hàng</h1>
        <p className="max-w-prose-narrow text-[15px] text-muted-foreground">Chọn sản phẩm cần trả trong đơn #{orderId}. Mỗi dòng được trả toàn bộ số lượng đã mua.</p>
      </header>

      <Alert variant="warning" title="Mỗi đơn chỉ gửi yêu cầu trả hàng một lần">
        Hãy kiểm tra kỹ tất cả sản phẩm cần trả trước khi xác nhận. Không thể bổ sung sản phẩm bằng một yêu cầu thứ hai.
      </Alert>

      <form onSubmit={submit} className="space-y-6">
        <Card>
          <CardHeader className="border-b border-border pb-4">
            <CardTitle>Chọn sản phẩm</CardTitle>
            <label className="flex min-h-11 items-center gap-3 text-[14px]">
              <Checkbox aria-label="Chọn tất cả sản phẩm" checked={selected.length === lines.length} indeterminate={selected.length > 0 && selected.length < lines.length} disabled={command.isPending} onCheckedChange={(checked) => setSelected(checked ? lines.map((line) => line.order_line_id) : [])} />
              Chọn tất cả ({lines.length})
            </label>
          </CardHeader>
          <CardContent className="space-y-1">
            {lines.map((line) => (
              <div key={line.order_line_id} className="relative pl-8">
                <label className="absolute left-0 top-8 grid size-11 place-content-center" aria-label={`Chọn ${line.snapshot_name}`}>
                  <Checkbox aria-label={`Chọn ${line.snapshot_name}`} checked={selected.includes(line.order_line_id)} disabled={command.isPending} onCheckedChange={(checked) => toggle(line.order_line_id, checked)} />
                </label>
                <OrderLineCard line={line} />
              </div>
            ))}
            {formError ? <p role="alert" className="pt-3 text-caption text-danger">{formError}</p> : null}
          </CardContent>
        </Card>

        <Card>
          <CardHeader><CardTitle>Lý do trả hàng</CardTitle></CardHeader>
          <CardContent className="space-y-5">
            <div className="space-y-2">
              <label htmlFor="return-reason" className="text-[14px] font-medium">Lý do</label>
              <NativeSelect id="return-reason" value={reason} disabled={command.isPending} onChange={(event) => setReason(event.target.value)}>
                {RETURN_REASONS.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}
              </NativeSelect>
            </div>
            <div className="space-y-2">
              <label htmlFor="return-details" className="text-[14px] font-medium">Mô tả thêm <span className="font-normal text-muted-foreground">(không bắt buộc)</span></label>
              <Textarea id="return-details" value={details} maxLength={1000} disabled={command.isPending} onChange={(event) => setDetails(event.target.value)} placeholder="Mô tả tình trạng sản phẩm để việc kiểm tra thuận lợi hơn" />
              <p className="text-caption text-muted-foreground">{details.length}/1000 ký tự</p>
            </div>
          </CardContent>
        </Card>

        {command.isError ? <ErrorState error={command.error} /> : null}
        <div className="flex flex-col-reverse gap-3 sm:flex-row sm:justify-end">
          <Link href={`/orders/${orderId}`} className="focus-ring inline-flex h-11 items-center justify-center rounded-lg px-5 text-[15px] font-medium text-foreground hover:bg-accent">Quay lại</Link>
          <Button type="submit" disabled={command.isPending || selected.length === 0 || isManualReviewOperation(command.data?.operation)}>
            {command.isPending ? <LoaderCircle className="animate-spin" aria-hidden /> : null}
            {command.isPending ? "Đang gửi yêu cầu…" : "Gửi yêu cầu trả hàng"}
          </Button>
        </div>
      </form>
    </main>
  );
}

function Back({ orderId }: { orderId: string }) {
  return <Link href={`/orders/${orderId}`} className="focus-ring inline-flex min-h-11 items-center gap-2 rounded-lg text-[14px] text-muted-foreground hover:text-foreground"><ArrowLeft className="size-4" aria-hidden />Chi tiết đơn hàng</Link>;
}
