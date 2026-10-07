"use client";

import { ChevronLeft, ChevronRight, Inbox } from "lucide-react";
import {
  flexRender,
  getCoreRowModel,
  useReactTable,
  type ColumnDef,
} from "@tanstack/react-table";

import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/empty-state";
import { ErrorState } from "@/components/ui/error-state";
import { Skeleton } from "@/components/ui/skeleton";
import { cn } from "@/lib/utils";

interface DataTableProps<TData> {
  columns: ColumnDef<TData, unknown>[];
  data: TData[];
  getRowId: (row: TData) => string;
  renderMobileCard: (row: TData) => React.ReactNode;
  caption: string;
  isLoading?: boolean;
  isFetching?: boolean;
  error?: unknown;
  partialError?: unknown;
  onRetry?: () => void;
  emptyTitle?: string;
  emptyDescription?: string;
  emptyAction?: React.ReactNode;
  page?: number;
  hasPrevious?: boolean;
  hasNext?: boolean;
  onPrevious?: () => void;
  onNext?: () => void;
  className?: string;
}

export function DataTable<TData>({
  columns,
  data,
  getRowId,
  renderMobileCard,
  caption,
  isLoading = false,
  isFetching = false,
  error,
  partialError,
  onRetry,
  emptyTitle = "Chưa có dữ liệu",
  emptyDescription = "Dữ liệu phù hợp sẽ xuất hiện tại đây.",
  emptyAction,
  page = 1,
  hasPrevious = false,
  hasNext = false,
  onPrevious,
  onNext,
  className,
}: DataTableProps<TData>) {
  // TanStack Table intentionally returns mutable adapter functions; React Compiler
  // skips this component, while the rest of the admin tree remains compilable.
  // eslint-disable-next-line react-hooks/incompatible-library
  const table = useReactTable({ data, columns, getRowId, getCoreRowModel: getCoreRowModel() });

  if (error) return <ErrorState error={error} onRetry={onRetry} />;

  return (
    <div className={cn("space-y-4", className)} aria-busy={isLoading || isFetching}>
      {partialError ? (
        <ErrorState
          error={partialError}
          onRetry={onRetry}
          message="Một phần dữ liệu chưa tải được. Bạn vẫn có thể xem danh sách bên dưới."
        />
      ) : null}

      {isLoading ? (
        <DataTableSkeleton columns={Math.max(columns.length, 1)} />
      ) : data.length === 0 ? (
        <EmptyState
          icon={Inbox}
          title={emptyTitle}
          description={emptyDescription}
          action={emptyAction}
        />
      ) : (
        <>
          <div className="hidden overflow-hidden rounded-xl border border-border bg-surface-overlay md:block">
            <table className="w-full border-collapse text-left">
              <caption className="sr-only">{caption}</caption>
              <thead className="bg-surface-2 text-caption font-medium uppercase tracking-wide text-muted-foreground">
                {table.getHeaderGroups().map((headerGroup) => (
                  <tr key={headerGroup.id}>
                    {headerGroup.headers.map((header) => (
                      <th key={header.id} scope="col" className="px-4 py-3">
                        {header.isPlaceholder
                          ? null
                          : flexRender(header.column.columnDef.header, header.getContext())}
                      </th>
                    ))}
                  </tr>
                ))}
              </thead>
              <tbody>
                {table.getRowModel().rows.map((row) => (
                  <tr
                    key={row.id}
                    className="border-b border-border transition-colors duration-200 last:border-0 hover:bg-surface-2"
                  >
                    {row.getVisibleCells().map((cell) => (
                      <td key={cell.id} className="px-4 py-3 text-[14px] align-top">
                        {flexRender(cell.column.columnDef.cell, cell.getContext())}
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <div className="space-y-3 md:hidden">
            {table.getRowModel().rows.map((row) => (
              <article
                key={row.id}
                className="rounded-xl border border-border bg-surface-overlay p-5"
              >
                {renderMobileCard(row.original)}
              </article>
            ))}
          </div>
        </>
      )}

      {!isLoading && (data.length > 0 || hasPrevious) ? (
        <nav
          aria-label="Phân trang"
          className="flex items-center justify-between gap-4 border-t border-border pt-4"
        >
          <Button
            type="button"
            variant="outline"
            size="sm"
            disabled={!hasPrevious || isFetching}
            onClick={onPrevious}
          >
            <ChevronLeft aria-hidden />
            Trang trước
          </Button>
          <span className="text-caption text-muted-foreground">Trang {page}</span>
          <Button
            type="button"
            variant="outline"
            size="sm"
            disabled={!hasNext || isFetching}
            onClick={onNext}
          >
            Trang sau
            <ChevronRight aria-hidden />
          </Button>
        </nav>
      ) : null}
    </div>
  );
}

function DataTableSkeleton({ columns }: { columns: number }) {
  return (
    <>
      <div className="hidden overflow-hidden rounded-xl border border-border bg-surface-overlay md:block">
        <div className="grid gap-4 bg-surface-2 px-4 py-3" style={{ gridTemplateColumns: `repeat(${columns}, minmax(0, 1fr))` }}>
          {Array.from({ length: columns }, (_, index) => (
            <Skeleton key={index} className="h-4 w-20" />
          ))}
        </div>
        {Array.from({ length: 5 }, (_, row) => (
          <div
            key={row}
            className="grid gap-4 border-t border-border px-4 py-4"
            style={{ gridTemplateColumns: `repeat(${columns}, minmax(0, 1fr))` }}
          >
            {Array.from({ length: columns }, (_, cell) => (
              <Skeleton key={cell} className="h-5 w-4/5" />
            ))}
          </div>
        ))}
      </div>
      <div className="space-y-3 md:hidden">
        {Array.from({ length: 4 }, (_, row) => (
          <div key={row} className="space-y-3 rounded-xl border border-border bg-surface-overlay p-5">
            <Skeleton className="h-5 w-2/3" />
            <Skeleton className="h-4 w-1/2" />
            <Skeleton className="h-4 w-4/5" />
          </div>
        ))}
      </div>
    </>
  );
}
