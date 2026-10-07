"use client";

import { useCallback, useMemo } from "react";
import Link from "next/link";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import type { ColumnDef } from "@tanstack/react-table";
import { ChevronRight, Search } from "lucide-react";

import { DataTable } from "@/components/admin/data-table";
import {
  localInputToVietnamIso,
  vietnamIsoToLocalInput,
} from "@/components/admin/date-time-input";
import { FilterBar } from "@/components/admin/filter-bar";
import { IdChip } from "@/components/admin/id-chip";
import { PageHeader } from "@/components/admin/page-header";
import { OrderCardStatus } from "@/components/orders/order-status";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { NativeSelect } from "@/components/ui/native-select";
import { useAdminOrders } from "@/hooks/use-admin-orders";
import type {
  AdminOrderListParams,
  BuyerTab,
  OrderCard,
  OrderStatus,
  PaymentStatus,
} from "@/lib/api/order";
import { formatDateTime, formatVND } from "@/lib/format";
import {
  BUYER_TAB_LABELS,
  ORDER_STATUS_LABELS,
  PAYMENT_STATUS_LABELS,
} from "@/lib/labels/order";

const BUYER_TABS = Object.keys(BUYER_TAB_LABELS) as BuyerTab[];
const ORDER_STATUSES = Object.keys(ORDER_STATUS_LABELS) as OrderStatus[];
const PAYMENT_STATUSES = Object.keys(PAYMENT_STATUS_LABELS) as PaymentStatus[];

function enumValue<T extends string>(values: readonly T[], value: string | null): T | undefined {
  return values.find((candidate) => candidate === value);
}

function amountValue(value: string | null): number | undefined {
  if (!value || !/^\d+$/.test(value)) return undefined;
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) ? parsed : undefined;
}

function filtersFrom(searchParams: URLSearchParams): AdminOrderListParams {
  const tab = enumValue(BUYER_TABS, searchParams.get("tab"));
  const orderStatus = enumValue(ORDER_STATUSES, searchParams.get("order_status"));
  const paymentStatus = enumValue(PAYMENT_STATUSES, searchParams.get("payment_status"));
  const buyerId = searchParams.get("buyer_id")?.trim();
  const q = searchParams.get("q")?.trim();
  const createdFrom = searchParams.get("created_from") ?? undefined;
  const createdTo = searchParams.get("created_to") ?? undefined;
  const minAmount = amountValue(searchParams.get("min_amount_minor"));
  const maxAmount = amountValue(searchParams.get("max_amount_minor"));

  return {
    ...(buyerId ? { buyer_id: buyerId } : {}),
    ...(tab ? { tab } : {}),
    ...(q ? { q } : {}),
    ...(createdFrom ? { created_from: createdFrom } : {}),
    ...(createdTo ? { created_to: createdTo } : {}),
    ...(orderStatus ? { order_status: [orderStatus] } : {}),
    ...(paymentStatus ? { payment_status: [paymentStatus] } : {}),
    ...(minAmount !== undefined ? { min_amount_minor: minAmount } : {}),
    ...(maxAmount !== undefined ? { max_amount_minor: maxAmount } : {}),
  };
}

function appendIfPresent(params: URLSearchParams, name: string, value: FormDataEntryValue | null) {
  const text = typeof value === "string" ? value.trim() : "";
  if (text) params.set(name, text);
}

export function AdminOrderList() {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const filters = useMemo(
    () => filtersFrom(new URLSearchParams(searchParams.toString())),
    [searchParams]
  );
  const ordersQuery = useAdminOrders(filters);
  const orders = ordersQuery.data?.items ?? [];

  const columns = useMemo<ColumnDef<OrderCard, unknown>[]>(
    () => [
      {
        accessorKey: "order_id",
        header: "Đơn hàng",
        cell: ({ row }) => (
          <div className="space-y-1">
            <Link
              href={`/admin/orders/${encodeURIComponent(row.original.order_id)}`}
              className="focus-ring rounded-sm font-medium hover:underline"
            >
              #{row.original.order_id}
            </Link>
            <p className="max-w-64 truncate text-caption text-muted-foreground">
              {row.original.primary_item_name}
              {row.original.remaining_item_count > 0
                ? ` +${row.original.remaining_item_count} dòng khác`
                : ""}
            </p>
          </div>
        ),
      },
      {
        accessorKey: "buyer_id",
        header: "Người mua",
        cell: ({ row }) => <IdChip value={row.original.buyer_id} label="Sao chép mã người mua" />,
      },
      {
        accessorKey: "ui_primary_status",
        header: "Trạng thái",
        cell: ({ row }) => <OrderCardStatus status={row.original.ui_primary_status} />,
      },
      {
        accessorKey: "final_amount",
        header: "Thành tiền",
        cell: ({ row }) => (
          <span className="whitespace-nowrap font-medium tabular-nums">
            {formatVND(row.original.final_amount.amount_minor)}
          </span>
        ),
      },
      {
        accessorKey: "created_at",
        header: "Ngày đặt",
        cell: ({ row }) => (
          <time dateTime={row.original.created_at} className="whitespace-nowrap tabular-nums">
            {formatDateTime(row.original.created_at)}
          </time>
        ),
      },
      {
        id: "actions",
        header: "",
        cell: ({ row }) => (
          <Button
            variant="ghost"
            size="icon-sm"
            nativeButton={false}
            aria-label={`Mở đơn hàng ${row.original.order_id}`}
            render={<Link href={`/admin/orders/${encodeURIComponent(row.original.order_id)}`} />}
          >
            <ChevronRight aria-hidden />
          </Button>
        ),
      },
    ],
    []
  );

  const resetFilters = useCallback(
    () => router.replace(pathname, { scroll: false }),
    [pathname, router]
  );

  function applyFilters(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const params = new URLSearchParams();
    appendIfPresent(params, "q", data.get("q"));
    appendIfPresent(params, "buyer_id", data.get("buyer_id"));
    appendIfPresent(params, "tab", data.get("tab"));
    appendIfPresent(params, "order_status", data.get("order_status"));
    appendIfPresent(params, "payment_status", data.get("payment_status"));
    appendIfPresent(params, "min_amount_minor", data.get("min_amount_minor"));
    appendIfPresent(params, "max_amount_minor", data.get("max_amount_minor"));

    const createdFrom = localInputToVietnamIso(String(data.get("created_from") ?? ""));
    const createdTo = localInputToVietnamIso(String(data.get("created_to") ?? ""));
    if (createdFrom) params.set("created_from", createdFrom);
    if (createdTo) params.set("created_to", createdTo);

    const query = params.toString();
    router.replace(query ? `${pathname}?${query}` : pathname, { scroll: false });
  }

  return (
    <div className="space-y-8">
      <PageHeader
        title="Đơn hàng"
        description={
          ordersQuery.data
            ? `${ordersQuery.data.total_orders.toLocaleString("vi-VN")} đơn phù hợp trên toàn hệ thống.`
            : "Tra cứu và xử lý vòng đời đơn hàng trên toàn hệ thống."
        }
      />

      <form key={searchParams.toString()} onSubmit={applyFilters}>
        <FilterBar hasFilters={searchParams.size > 0} onReset={resetFilters}>
          <FilterField label="Tìm kiếm" htmlFor="admin-order-q">
            <Input
              id="admin-order-q"
              name="q"
              maxLength={200}
              defaultValue={searchParams.get("q") ?? ""}
              placeholder="Tên sản phẩm"
            />
          </FilterField>
          <FilterField label="Mã người mua" htmlFor="admin-order-buyer">
            <Input
              id="admin-order-buyer"
              name="buyer_id"
              type="text"
              pattern="[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}"
              defaultValue={searchParams.get("buyer_id") ?? ""}
              placeholder="UUID người mua"
            />
          </FilterField>
          <FilterField label="Nhóm xử lý" htmlFor="admin-order-tab">
            <NativeSelect id="admin-order-tab" name="tab" defaultValue={searchParams.get("tab") ?? ""}>
              <option value="">Tất cả nhóm</option>
              {BUYER_TABS.map((value) => (
                <option key={value} value={value}>{BUYER_TAB_LABELS[value].text}</option>
              ))}
            </NativeSelect>
          </FilterField>
          <FilterField label="Trạng thái đơn" htmlFor="admin-order-status">
            <NativeSelect id="admin-order-status" name="order_status" defaultValue={searchParams.get("order_status") ?? ""}>
              <option value="">Tất cả trạng thái đơn</option>
              {ORDER_STATUSES.map((value) => (
                <option key={value} value={value}>{ORDER_STATUS_LABELS[value].text}</option>
              ))}
            </NativeSelect>
          </FilterField>
          <FilterField label="Trạng thái thanh toán" htmlFor="admin-order-payment-status">
            <NativeSelect id="admin-order-payment-status" name="payment_status" defaultValue={searchParams.get("payment_status") ?? ""}>
              <option value="">Tất cả thanh toán</option>
              {PAYMENT_STATUSES.map((value) => (
                <option key={value} value={value}>{PAYMENT_STATUS_LABELS[value].text}</option>
              ))}
            </NativeSelect>
          </FilterField>
          <FilterField label="Tạo từ" htmlFor="admin-order-created-from">
            <Input id="admin-order-created-from" name="created_from" type="datetime-local" defaultValue={vietnamIsoToLocalInput(searchParams.get("created_from") ?? undefined)} />
          </FilterField>
          <FilterField label="Tạo đến" htmlFor="admin-order-created-to">
            <Input id="admin-order-created-to" name="created_to" type="datetime-local" defaultValue={vietnamIsoToLocalInput(searchParams.get("created_to") ?? undefined)} />
          </FilterField>
          <FilterField label="Số tiền từ" htmlFor="admin-order-min-amount">
            <Input id="admin-order-min-amount" name="min_amount_minor" type="number" min={0} step={1} defaultValue={searchParams.get("min_amount_minor") ?? ""} />
          </FilterField>
          <FilterField label="Số tiền đến" htmlFor="admin-order-max-amount">
            <Input id="admin-order-max-amount" name="max_amount_minor" type="number" min={0} step={1} defaultValue={searchParams.get("max_amount_minor") ?? ""} />
          </FilterField>
          <div className="flex items-end">
            <Button type="submit" className="w-full sm:w-auto">
              <Search aria-hidden />
              Áp dụng
            </Button>
          </div>
        </FilterBar>
      </form>

      <DataTable
        columns={columns}
        data={orders}
        getRowId={(order) => order.order_id}
        caption="Danh sách đơn hàng quản trị"
        isLoading={ordersQuery.isPending}
        isFetching={ordersQuery.isFetching && !ordersQuery.isPending}
        error={ordersQuery.error}
        onRetry={() => void ordersQuery.refetch()}
        emptyTitle="Không có đơn hàng phù hợp"
        emptyDescription="Hãy thử thay đổi bộ lọc hoặc từ khoá tìm kiếm."
        emptyAction={searchParams.size > 0 ? <Button variant="outline" size="sm" onClick={resetFilters}>Xoá bộ lọc</Button> : undefined}
        page={ordersQuery.page}
        hasPrevious={ordersQuery.hasPrevious}
        hasNext={ordersQuery.hasNext}
        onPrevious={ordersQuery.goPrevious}
        onNext={ordersQuery.goNext}
        renderMobileCard={(order) => (
          <Link href={`/admin/orders/${encodeURIComponent(order.order_id)}`} className="focus-ring block space-y-4 rounded-lg">
            <div className="flex items-start justify-between gap-4">
              <div className="min-w-0 space-y-1">
                <h2 className="font-mono text-[15px] font-medium">#{order.order_id}</h2>
                <p className="truncate text-caption text-muted-foreground">{order.primary_item_name}</p>
              </div>
              <OrderCardStatus status={order.ui_primary_status} />
            </div>
            <dl className="grid grid-cols-2 gap-3 text-[14px]">
              <div><dt className="text-caption text-muted-foreground">Thành tiền</dt><dd className="font-medium tabular-nums">{formatVND(order.final_amount.amount_minor)}</dd></div>
              <div className="text-right"><dt className="text-caption text-muted-foreground">Số dòng</dt><dd>{order.item_count}</dd></div>
              <div className="col-span-2"><dt className="text-caption text-muted-foreground">Ngày đặt</dt><dd>{formatDateTime(order.created_at)}</dd></div>
            </dl>
          </Link>
        )}
      />
    </div>
  );
}

function FilterField({ label, htmlFor, children }: { label: string; htmlFor: string; children: React.ReactNode }) {
  return <div className="space-y-2"><Label htmlFor={htmlFor}>{label}</Label>{children}</div>;
}
