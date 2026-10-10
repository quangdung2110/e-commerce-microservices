"use client";
import { useRouter, useSearchParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { PageHeader } from "@/components/admin/page-header";
import { Button } from "@/components/ui/button";
import { ErrorState } from "@/components/ui/error-state";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";
import { inventoryAdminApi } from "@/lib/api/inventory-admin";
import { RefundScopeDetail } from "@/components/admin/inventory/refund-scope-detail";

export function ReturnLookup() {
  const router = useRouter();
  const params = useSearchParams();
  const orderId = params.get("order_id") ?? "";
  const query = useQuery({
    queryKey: ["inventory", "refund-scope-by-order", orderId],
    queryFn: ({ signal }) =>
      inventoryAdminApi.findRefundScopeByOrder(orderId, signal),
    enabled: !!orderId,
  });
  return (
    <div className="space-y-8">
      <PageHeader
        title="Tra cứu hàng trả"
        description="Tìm yêu cầu trả hàng theo mã đơn. Yêu cầu phải được backend đăng ký trước khi kho nhận hàng."
      />
      <form
        key={orderId}
        className="space-y-5 rounded-xl border border-border bg-surface-overlay p-6"
        onSubmit={(event) => {
          event.preventDefault();
          const form = new FormData(event.currentTarget);
          router.replace(
            `/admin/inventory/returns?order_id=${encodeURIComponent(String(form.get("order_id") ?? "").trim())}`,
          );
        }}
      >
        <Label htmlFor="return-order">Mã đơn hàng *</Label>
        <Input
          id="return-order"
          name="order_id"
          maxLength={128}
          required
          defaultValue={orderId}
        />
        <Button type="submit">Tìm yêu cầu trả hàng</Button>
      </form>
      {orderId ? (
        query.isPending ? (
          <Skeleton className="h-64 rounded-xl" />
        ) : query.data ? (
          <RefundScopeDetail
            key={query.data.refund_scope_id}
            scopeId={query.data.refund_scope_id}
          />
        ) : (
          <ErrorState
            error={query.error}
            onRetry={() => void query.refetch()}
          />
        )
      ) : null}
    </div>
  );
}
