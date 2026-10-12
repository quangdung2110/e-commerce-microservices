"use client";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import type { ColumnDef } from "@tanstack/react-table";
import { DataTable } from "@/components/admin/data-table";
import { IdChip } from "@/components/admin/id-chip";
import { FieldBlock } from "@/components/admin/inventory/inventory-shared";
import { PageHeader } from "@/components/admin/page-header";
import { StatusBadge } from "@/components/admin/status-badge";
import { Button } from "@/components/ui/button";
import { ErrorState } from "@/components/ui/error-state";
import { Input } from "@/components/ui/input";
import { NativeSelect } from "@/components/ui/native-select";
import { Skeleton } from "@/components/ui/skeleton";
import { useCursorPager } from "@/hooks/use-cursor-list";
import {
  inventoryAdminApi,
  type AdminRefundScopeSummary,
} from "@/lib/api/inventory-admin";
import { formatDateTime } from "@/lib/format";
import { REFUND_SCOPE_LABELS } from "@/lib/labels/inventory";
import { RefundScopeDetail } from "@/components/admin/inventory/refund-scope-detail";

const STATES = Object.keys(REFUND_SCOPE_LABELS).filter(
  (value) => value !== "UNKNOWN",
) as AdminRefundScopeSummary["state"][];

export function ReturnLookup() {
  const router = useRouter();
  const params = useSearchParams();
  const orderId = params.get("order_id") ?? "";
  const rawState = params.get("state") ?? "";
  const state = STATES.find((value) => value === rawState);
  return (
    <div className="space-y-8">
      <PageHeader
        title="Hàng trả"
        description="Toàn bộ yêu cầu trả hàng đã được đăng ký với kho. Nhập mã đơn hoặc chọn trạng thái để thu hẹp danh sách."
      />
      <form
        key={`${orderId}:${state ?? ""}`}
        className="grid gap-5 rounded-xl border border-border bg-surface-2 p-6 sm:grid-cols-2 lg:grid-cols-3"
        onSubmit={(event) => {
          event.preventDefault();
          const form = new FormData(event.currentTarget);
          const next = new URLSearchParams();
          const order = String(form.get("order_id") ?? "").trim();
          const status = String(form.get("state") ?? "");
          if (order) next.set("order_id", order);
          if (status) next.set("state", status);
          router.replace(`/admin/inventory/returns?${next}`);
        }}
      >
        <FieldBlock id="return-order" label="Mã đơn hàng">
          <Input
            id="return-order"
            name="order_id"
            maxLength={128}
            defaultValue={orderId}
            placeholder="Để trống để xem tất cả đơn"
          />
        </FieldBlock>
        <FieldBlock id="return-state" label="Trạng thái">
          <NativeSelect
            id="return-state"
            name="state"
            defaultValue={state ?? ""}
            disabled={!!orderId}
          >
            <option value="">Tất cả</option>
            {STATES.map((value) => (
              <option key={value} value={value}>
                {REFUND_SCOPE_LABELS[value].text}
              </option>
            ))}
          </NativeSelect>
        </FieldBlock>
        <div className="flex items-end gap-2">
          <Button type="submit">Áp dụng lọc</Button>
          <Button
            variant="ghost"
            type="button"
            onClick={() => router.replace("/admin/inventory/returns")}
          >
            Xoá lọc
          </Button>
        </div>
      </form>
      {orderId ? <ScopeByOrder orderId={orderId} /> : <ScopeList state={state} />}
    </div>
  );
}

function ScopeByOrder({ orderId }: { orderId: string }) {
  const query = useQuery({
    queryKey: ["inventory", "refund-scope-by-order", orderId],
    queryFn: ({ signal }) =>
      inventoryAdminApi.findRefundScopeByOrder(orderId, signal),
  });
  if (query.isPending) return <Skeleton className="h-64 rounded-xl" />;
  if (!query.data)
    return <ErrorState error={query.error} onRetry={() => void query.refetch()} />;
  return (
    <RefundScopeDetail
      key={query.data.refund_scope_id}
      scopeId={query.data.refund_scope_id}
    />
  );
}

const COLUMNS: ColumnDef<AdminRefundScopeSummary, unknown>[] = [
  {
    id: "order",
    header: "Đơn hàng",
    cell: ({ row }) => <IdChip value={row.original.order_id} />,
  },
  {
    id: "state",
    header: "Trạng thái",
    cell: ({ row }) => (
      <StatusBadge value={row.original.state} labels={REFUND_SCOPE_LABELS} />
    ),
  },
  {
    id: "registered",
    header: "Đăng ký lúc",
    cell: ({ row }) => formatDateTime(row.original.registered_at),
  },
  {
    id: "action",
    header: "Thao tác",
    cell: ({ row }) => <OpenScope scope={row.original} />,
  },
];

function OpenScope({ scope }: { scope: AdminRefundScopeSummary }) {
  return (
    <Button
      variant="outline"
      nativeButton={false}
      render={
        <Link
          href={`/admin/inventory/refund-scopes/${encodeURIComponent(scope.refund_scope_id)}`}
        />
      }
    >
      Xem yêu cầu trả hàng
    </Button>
  );
}

function ScopeList({ state }: { state?: AdminRefundScopeSummary["state"] }) {
  const list = useCursorPager<{
    items: AdminRefundScopeSummary[];
    next_cursor: string | null;
  }>({
    queryKey: ["inventory", "refund-scopes", state ?? ""],
    fetchPage: (cursor, signal) =>
      inventoryAdminApi.listRefundScopes({ state, cursor, limit: 25 }, signal),
  });
  return (
    <DataTable
      columns={COLUMNS}
      data={list.data?.items ?? []}
      getRowId={(row) => row.refund_scope_id}
      caption="Yêu cầu trả hàng"
      renderMobileCard={(row) => (
        <div className="space-y-4">
          <IdChip value={row.order_id} />
          <StatusBadge value={row.state} labels={REFUND_SCOPE_LABELS} />
          <p className="text-caption">{formatDateTime(row.registered_at)}</p>
          <OpenScope scope={row} />
        </div>
      )}
      isLoading={list.isPending}
      isFetching={list.isFetching}
      error={!list.data ? list.error : undefined}
      partialError={list.data ? list.error : undefined}
      onRetry={() => {
        list.reset();
        void list.refetch();
      }}
      emptyTitle="Chưa có yêu cầu trả hàng"
      emptyDescription="Yêu cầu trả hàng xuất hiện ở đây sau khi đơn hàng đăng ký hoàn trả với kho."
      emptyAction={
        <Button variant="outline" onClick={() => void list.refetch()}>
          Tải lại
        </Button>
      }
      page={list.page}
      hasNext={!list.isPlaceholderData && list.hasNext}
      hasPrevious={list.hasPrevious}
      onNext={list.goNext}
      onPrevious={list.goPrevious}
    />
  );
}
