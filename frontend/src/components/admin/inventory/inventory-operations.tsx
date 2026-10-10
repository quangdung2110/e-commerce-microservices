"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import type { ColumnDef } from "@tanstack/react-table";
import { DataTable } from "@/components/admin/data-table";
import { DateTimeInput, localInputToVietnamIso } from "@/components/admin/date-time-input";
import { FieldBlock } from "@/components/admin/inventory/inventory-shared";
import {
  ReturnCommandForm,
  type ReturnAction,
} from "@/components/admin/inventory/return-command-form";
import { PageHeader } from "@/components/admin/page-header";
import { IdChip } from "@/components/admin/id-chip";
import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { NativeSelect } from "@/components/ui/native-select";
import { useCursorPager } from "@/hooks/use-cursor-list";
import { inventoryAdminApi } from "@/lib/api/inventory-admin";
import { formatDateTime } from "@/lib/format";
import {
  FAIL_REASON_LABELS,
  DISPOSITION_REASON_LABELS,
  MOVEMENT_OPERATION_LABELS,
} from "@/lib/labels/inventory";

const VIEWS = {
  warehouses: "Danh sách kho",
  positions: "Tồn kho",
  movements: "Lịch sử biến động",
  dispatch: "Chờ xuất kho",
  inspection: "Chờ kiểm định",
  quarantine: "Hàng cách ly",
} as const;
type View = keyof typeof VIEWS;
type InventoryRow =
  | Awaited<
      ReturnType<typeof inventoryAdminApi.listWarehouses>
    >["items"][number]
  | Awaited<
      ReturnType<typeof inventoryAdminApi.listStockPositions>
    >["items"][number]
  | Awaited<
      ReturnType<typeof inventoryAdminApi.listStockMovements>
    >["items"][number]
  | Awaited<
      ReturnType<typeof inventoryAdminApi.listDispatchQueue>
    >["items"][number]
  | Awaited<
      ReturnType<typeof inventoryAdminApi.listInspectionQueue>
    >["items"][number]
  | Awaited<
      ReturnType<typeof inventoryAdminApi.listQuarantineItems>
    >["items"][number];

export function InventoryOperations() {
  const router = useRouter();
  const params = useSearchParams();
  const rawView = params.get("view") ?? "warehouses";
  const view: View = Object.hasOwn(VIEWS, rawView)
    ? (rawView as View)
    : "warehouses";
  const warehouse = params.get("warehouse_id") ?? "";
  const sku = params.get("sku_id") ?? "";
  const active = params.get("is_active") ?? "";
  const from = params.get("recorded_from") ?? undefined;
  const to = params.get("recorded_to") ?? undefined;
  const [busy, setBusy] = useState(false);
  const [action, setAction] = useState<ReturnAction | null>(null);
  const requiresWarehouse =
    view === "dispatch" || view === "inspection" || view === "quarantine";
  const enabled =
    view === "warehouses" ||
    (requiresWarehouse ? !!warehouse : !!warehouse || !!sku);
  const list = useCursorPager<{
    items: InventoryRow[];
    next_cursor: string | null;
  }>({
    queryKey: [
      "inventory",
      "operations",
      view,
      warehouse,
      sku,
      active,
      from,
      to,
    ],
    enabled,
    fetchPage: (cursor, signal) => {
      const common = {
        warehouse_id: warehouse || undefined,
        sku_id: sku || undefined,
        cursor,
        limit: 25,
      };
      switch (view) {
        case "warehouses":
          return inventoryAdminApi.listWarehouses(
            {
              cursor,
              limit: 25,
              is_active: active === "" ? undefined : active === "true",
            },
            signal,
          );
        case "positions":
          return inventoryAdminApi.listStockPositions(common, signal);
        case "movements":
          return inventoryAdminApi.listStockMovements(
            { ...common, recorded_from: from, recorded_to: to },
            signal,
          );
        case "dispatch":
          return inventoryAdminApi.listDispatchQueue(
            { warehouse_id: warehouse, cursor, limit: 25 },
            signal,
          );
        case "inspection":
          return inventoryAdminApi.listInspectionQueue(
            { warehouse_id: warehouse, cursor, limit: 25 },
            signal,
          );
        case "quarantine":
          return inventoryAdminApi.listQuarantineItems(
            { warehouse_id: warehouse, cursor, limit: 25 },
            signal,
          );
      }
    },
  });
  function change(values: Record<string, string | undefined>) {
    const next = new URLSearchParams(params);
    for (const [key, value] of Object.entries(values)) {
      if (value) next.set(key, value);
      else next.delete(key);
    }
    router.replace(`/admin/inventory/operations?${next}`);
  }
  function details(row: InventoryRow) {
    if ("name" in row)
      return (
        <>
          <span>{row.name}</span>
          <p className="text-caption text-muted-foreground">
            {row.is_active ? "Nhận phân bổ mới" : "Ngừng nhận phân bổ"} · Ưu
            tiên {row.fulfillment_priority}
          </p>
        </>
      );
    if ("available_to_sell" in row)
      return (
        <dl className="grid grid-cols-2 gap-2 text-caption">
          <dt>Tồn thực tế</dt>
          <dd>{row.on_hand}</dd>
          <dt>Bán được</dt>
          <dd>{row.available_to_sell}</dd>
          <dt>Giữ / cam kết</dt>
          <dd>
            {row.reserved} / {row.committed}
          </dd>
          <dt>Cách ly</dt>
          <dd>{row.quarantine}</dd>
        </dl>
      );
    if ("movement_id" in row)
      return (
        <>
          <p>
            {MOVEMENT_OPERATION_LABELS[row.source_operation_type] ??
              "Biến động tồn kho"}
          </p>
          <p className="text-caption">{formatDateTime(row.recorded_at)}</p>
        </>
      );
    if ("uninspected_quantity" in row)
      return (
        <>
          <p>
            Chưa kiểm định: {row.uninspected_quantity} / {row.received_quantity}
          </p>
          <p className="text-caption">
            Phiếu {row.return_receipt_id} · {row.return_receipt_line_id}
          </p>
        </>
      );
    if ("disposable_quantity" in row)
      return (
        <>
          <p>
            Có thể xử lý: {row.disposable_quantity} / {row.failed_quantity}
          </p>
          <p className="text-caption">{FAIL_REASON_LABELS[row.fail_reason]}</p>
          {row.latest_hold_reason ? (
            <p className="text-caption">
              {DISPOSITION_REASON_LABELS[row.latest_hold_reason]}
            </p>
          ) : null}
        </>
      );
    return (
      <>
        <p>Số lượng: {row.quantity}</p>
        <p className="text-caption">{formatDateTime(row.committed_at)}</p>
      </>
    );
  }
  function rowAction(row: InventoryRow) {
    let href: string | undefined;
    if ("name" in row)
      href = `/admin/inventory/warehouses/${encodeURIComponent(row.warehouse_id)}`;
    else if ("movement_id" in row)
      href = `/admin/inventory/movements/${encodeURIComponent(row.movement_id)}`;
    else if ("available_to_sell" in row)
      href = `/admin/inventory/stock/${encodeURIComponent(row.warehouse_id)}/${encodeURIComponent(row.sku_id)}`;
    else if ("uninspected_quantity" in row)
      return (
        <div className="flex flex-wrap gap-2">
          <Button
            variant="outline"
            onClick={() =>
              setAction({
                kind: "inspection",
                warehouse_id: row.warehouse_id,
                return_receipt_id: row.return_receipt_id,
                return_receipt_line_id: row.return_receipt_line_id,
                max: row.uninspected_quantity,
              })
            }
          >
            Kiểm định
          </Button>
          <Link
            className="focus-ring inline-flex min-h-11 items-center text-caption underline"
            href={`/admin/inventory/refund-scopes/${encodeURIComponent(row.refund_scope_id)}`}
          >
            Yêu cầu trả hàng
          </Link>
        </div>
      );
    else if ("disposable_quantity" in row)
      return (
        <Button
          variant="outline"
          disabled={row.disposable_quantity <= 0}
          onClick={() =>
            setAction({
              kind: "disposition",
              warehouse_id: row.warehouse_id,
              sku_id: row.sku_id,
              inspection_id: row.inspection_id,
              max: row.disposable_quantity,
            })
          }
        >
          Xử lý cách ly
        </Button>
      );
    else
      href = `/admin/inventory/reservations/${encodeURIComponent(row.order_id)}`;
    return (
      <Button
        variant="outline"
        nativeButton={false}
        render={<Link href={href} />}
      >
        Xem chi tiết
      </Button>
    );
  }
  function identity(row: InventoryRow) {
    return (
      <div className="space-y-2">
        <IdChip value={row.warehouse_id} />
        {"sku_id" in row ? (
          <p>
            SKU: <IdChip value={row.sku_id} />
          </p>
        ) : null}
        {"order_id" in row ? (
          <p>
            Đơn: <IdChip value={row.order_id} />
          </p>
        ) : null}
      </div>
    );
  }
  const columns: ColumnDef<InventoryRow, unknown>[] = [
    {
      id: "identity",
      header: "Kho / SKU / đơn",
      cell: ({ row }) => identity(row.original),
    },
    {
      id: "details",
      header: VIEWS[view],
      cell: ({ row }) => details(row.original),
    },
    {
      id: "action",
      header: "Thao tác",
      cell: ({ row }) => rowAction(row.original),
    },
  ];
  return (
    <div className="space-y-8">
      <PageHeader
        title="Vận hành kho"
        description="Danh sách và hàng đợi theo dữ liệu Inventory. Chọn kho để kiểm định và xử lý cách ly."
        action={
          <Button
            variant="outline"
            onClick={() => setAction({ kind: "reversal" })}
          >
            Đảo xử lý cách ly
          </Button>
        }
      />
      <div className="flex flex-wrap gap-2" aria-label="Màn vận hành kho">
        {Object.entries(VIEWS).map(([value, label]) => (
          <Button
            key={value}
            variant={view === value ? "secondary" : "ghost"}
            aria-current={view === value ? "page" : undefined}
            onClick={() => change({ view: value })}
          >
            {label}
          </Button>
        ))}
      </div>
      <form
        key={`${view}:${warehouse}:${sku}:${active}:${from}:${to}`}
        className="grid gap-5 rounded-xl border border-border bg-surface-2 p-6 sm:grid-cols-2 lg:grid-cols-3"
        onSubmit={(event) => {
          event.preventDefault();
          const data = new FormData(event.currentTarget);
          change({
            warehouse_id: String(data.get("warehouse_id") ?? "").trim(),
            sku_id: String(data.get("sku_id") ?? "").trim(),
            is_active: String(data.get("is_active") ?? ""),
            ...(view === "movements" ? {
              recorded_from: localInputToVietnamIso(String(data.get("recorded_from") ?? "")),
              recorded_to: localInputToVietnamIso(String(data.get("recorded_to") ?? "")),
            } : {}),
          });
        }}
      >
        {view === "warehouses" ? (
          <FieldBlock id="is_active" label="Nhận phân bổ">
            <NativeSelect id="is_active" name="is_active" defaultValue={active}>
              <option value="">Tất cả</option>
              <option value="true">Đang nhận</option>
              <option value="false">Ngừng nhận</option>
            </NativeSelect>
          </FieldBlock>
        ) : (
          <>
            <FieldBlock
              id="warehouse_id"
              label={requiresWarehouse ? "Mã kho *" : "Mã kho"}
            >
              <Input
                id="warehouse_id"
                name="warehouse_id"
                defaultValue={warehouse}
                maxLength={128}
                required={requiresWarehouse}
              />
            </FieldBlock>
            {!requiresWarehouse ? (
              <FieldBlock id="sku_id" label="Mã SKU">
                <Input
                  id="sku_id"
                  name="sku_id"
                  defaultValue={sku}
                  maxLength={128}
                />
              </FieldBlock>
            ) : null}
          </>
        )}
        {view === "movements" ? (
          <MovementDateFields from={from} to={to} />
        ) : null}
        <div className="flex items-end gap-2">
          <Button type="submit">Áp dụng lọc</Button>
          <Button
            variant="ghost"
            type="button"
            onClick={() =>
              change({
                warehouse_id: undefined,
                sku_id: undefined,
                is_active: undefined,
                recorded_from: undefined,
                recorded_to: undefined,
              })
            }
          >
            Xoá lọc
          </Button>
        </div>
      </form>
      {!enabled ? (
        <Alert variant="info" title="Chọn phạm vi tra cứu">
          {requiresWarehouse
            ? "Nhập mã kho để tải hàng đợi."
            : "Nhập ít nhất mã kho hoặc mã SKU để tra cứu."}
        </Alert>
      ) : (
        <DataTable
          columns={columns}
          data={list.data?.items ?? []}
          getRowId={(row) =>
            "movement_id" in row
              ? row.movement_id
              : "inspection_id" in row
                ? row.inspection_id
                : "return_receipt_id" in row
                  ? JSON.stringify([
                      row.return_receipt_id,
                      row.return_receipt_line_id,
                    ])
                  : "order_line_id" in row
                    ? JSON.stringify([row.order_id, row.order_line_id])
                    : JSON.stringify([
                        row.warehouse_id,
                        "sku_id" in row ? row.sku_id : "",
                      ])
          }
          caption={VIEWS[view]}
          renderMobileCard={(row) => (
            <div className="space-y-4">
              {identity(row)}
              {details(row)}
              {rowAction(row)}
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
          emptyTitle="Không có dữ liệu phù hợp"
          emptyDescription="Hãy chọn kho hoặc bộ lọc khác, hoặc tải lại danh sách."
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
      )}
      <Dialog
        open={action !== null}
        onOpenChange={(open) => {
          if (!open && !busy) setAction(null);
        }}
        disablePointerDismissal={busy}
      >
        <DialogContent showCloseButton={!busy}>
          <DialogHeader>
            <DialogTitle>
              {action?.kind === "inspection"
                ? "Kiểm định hàng trả"
                : action?.kind === "reversal"
                  ? "Đảo xử lý cách ly"
                  : "Xử lý hàng cách ly"}
            </DialogTitle>
            <DialogDescription>
              Đối chiếu chứng từ và hàng thực tế trước khi ghi nhận.
            </DialogDescription>
          </DialogHeader>
          <div className="mt-6">
            {action ? (
              <ReturnCommandForm action={action} onBusyChange={setBusy} />
            ) : null}
          </div>
        </DialogContent>
      </Dialog>
    </div>
  );
}

function MovementDateFields({ from, to }: { from?: string; to?: string }) {
  const [draftFrom, setDraftFrom] = useState(from);
  const [draftTo, setDraftTo] = useState(to);
  return <>
    <FieldBlock id="recorded-from" label="Từ thời điểm"><DateTimeInput id="recorded-from" name="recorded_from" value={draftFrom} onValueChange={setDraftFrom} /></FieldBlock>
    <FieldBlock id="recorded-to" label="Đến thời điểm"><DateTimeInput id="recorded-to" name="recorded_to" value={draftTo} onValueChange={setDraftTo} /></FieldBlock>
  </>;
}
