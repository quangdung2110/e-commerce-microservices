"use client";

import { useCallback, useMemo } from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import type { ColumnDef } from "@tanstack/react-table";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { ChevronRight, Plus } from "lucide-react";

import { DataTable } from "@/components/admin/data-table";
import { FilterBar } from "@/components/admin/filter-bar";
import { IdChip } from "@/components/admin/id-chip";
import { PageHeader } from "@/components/admin/page-header";
import { StatusBadge } from "@/components/admin/status-badge";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { NativeSelect } from "@/components/ui/native-select";
import { useCursorPager } from "@/hooks/use-cursor-list";
import { formatDateTime } from "@/lib/format";
import {
  productApi,
  type Category,
  type ProductStatus,
  type ProductSummary,
} from "@/lib/api/product";
import { PRODUCT_STATUS_LABELS } from "@/lib/labels/product";

const PRODUCT_STATUSES = ["DRAFT", "ACTIVE", "HIDDEN"] as const;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

function parseStatus(value: string | null): ProductStatus | undefined {
  return PRODUCT_STATUSES.find((status) => status === value);
}

function categoryName(categoryById: Map<string, Category>, id: string) {
  return categoryById.get(id)?.name;
}

export function ProductList() {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const status = parseStatus(searchParams.get("status"));
  const rawCategoryId = searchParams.get("category_id");
  const categoryId = rawCategoryId && UUID.test(rawCategoryId) ? rawCategoryId : undefined;

  const categoriesQuery = useQuery({
    queryKey: ["product", "categories"],
    queryFn: ({ signal }) => productApi.listCategories({}, signal),
    staleTime: 5 * 60_000,
  });

  const productsQuery = useCursorPager({
    queryKey: ["product", "products", { status: status ?? null, categoryId: categoryId ?? null }],
    fetchPage: (cursor, signal) =>
      productApi.listProducts(
        {
          limit: 20,
          cursor,
          status,
          category_id: categoryId,
        },
        signal
      ),
  });

  const categoryById = useMemo(
    () => new Map((categoriesQuery.data ?? []).map((category) => [category.category_id, category])),
    [categoriesQuery.data]
  );

  const columns = useMemo<ColumnDef<ProductSummary, unknown>[]>(
    () => [
      {
        accessorKey: "name",
        header: "Tên sản phẩm",
        cell: ({ row }) => (
          <div className="max-w-64 space-y-1">
            <Link
              href={`/admin/products/${row.original.product_id}`}
              className="focus-ring rounded-sm font-medium text-foreground hover:underline"
            >
              {row.original.name}
            </Link>
            <p className="truncate font-mono text-caption text-muted-foreground">
              {row.original.slug}
            </p>
          </div>
        ),
      },
      {
        accessorKey: "category_id",
        header: "Danh mục",
        cell: ({ row }) =>
          categoryName(categoryById, row.original.category_id) ?? (
            <IdChip value={row.original.category_id} label="Sao chép mã danh mục" />
          ),
      },
      {
        accessorKey: "status",
        header: "Trạng thái",
        cell: ({ row }) => (
          <StatusBadge value={row.original.status} labels={PRODUCT_STATUS_LABELS} />
        ),
      },
      {
        accessorKey: "catalog_revision",
        header: "Phiên bản",
        cell: ({ row }) => (
          <span className="font-mono tabular-nums">{row.original.catalog_revision}</span>
        ),
      },
      {
        accessorKey: "updated_at",
        header: "Cập nhật",
        cell: ({ row }) => (
          <time dateTime={row.original.updated_at} className="whitespace-nowrap tabular-nums">
            {formatDateTime(row.original.updated_at)}
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
            aria-label={`Mở sản phẩm ${row.original.name}`}
            render={<Link href={`/admin/products/${row.original.product_id}`} />}
          >
            <ChevronRight aria-hidden />
          </Button>
        ),
      },
    ],
    [categoryById]
  );

  const updateFilter = useCallback(
    (name: "status" | "category_id", value: string) => {
      const params = new URLSearchParams(searchParams.toString());
      if (value) params.set(name, value);
      else params.delete(name);
      const query = params.toString();
      router.replace(query ? `${pathname}?${query}` : pathname, { scroll: false });
    },
    [pathname, router, searchParams]
  );

  const resetFilters = useCallback(() => router.replace(pathname, { scroll: false }), [pathname, router]);
  const hasFilters = Boolean(status || categoryId || rawCategoryId);
  const products = productsQuery.data?.items ?? [];

  return (
    <div className="space-y-8">
      <PageHeader
        title="Sản phẩm"
        description="Tạo và quản lý thông tin, SKU, media cùng trạng thái bán của sản phẩm."
        action={
          <Button render={<Link href="/admin/products/new" />}>
            <Plus aria-hidden />
            Tạo sản phẩm
          </Button>
        }
      />

      <FilterBar hasFilters={hasFilters} onReset={resetFilters}>
        <div className="space-y-2">
          <Label htmlFor="product-status-filter">Trạng thái</Label>
          <NativeSelect
            id="product-status-filter"
            value={status ?? ""}
            onChange={(event) => updateFilter("status", event.target.value)}
          >
            <option value="">Tất cả trạng thái</option>
            {PRODUCT_STATUSES.map((value) => (
              <option key={value} value={value}>
                {PRODUCT_STATUS_LABELS[value].text}
              </option>
            ))}
          </NativeSelect>
        </div>

        <div className="space-y-2">
          <Label htmlFor="product-category-filter">Danh mục</Label>
          <NativeSelect
            id="product-category-filter"
            value={categoryId ?? ""}
            disabled={categoriesQuery.isPending}
            onChange={(event) => updateFilter("category_id", event.target.value)}
          >
            <option value="">
              {categoriesQuery.isPending ? "Đang tải danh mục…" : "Tất cả danh mục"}
            </option>
            {(categoriesQuery.data ?? []).map((category) => (
              <option key={category.category_id} value={category.category_id}>
                {category.name}
              </option>
            ))}
          </NativeSelect>
        </div>
      </FilterBar>

      <DataTable
        columns={columns}
        data={products}
        getRowId={(product) => product.product_id}
        caption="Danh sách sản phẩm quản trị"
        isLoading={productsQuery.isPending}
        isFetching={productsQuery.isFetching && !productsQuery.isPending}
        error={productsQuery.error}
        partialError={categoriesQuery.error}
        onRetry={() => {
          void productsQuery.refetch();
          if (categoriesQuery.isError) void categoriesQuery.refetch();
        }}
        emptyTitle="Không có sản phẩm phù hợp"
        emptyDescription={
          hasFilters
            ? "Hãy thử bỏ bớt bộ lọc để xem các sản phẩm khác."
            : "Sản phẩm được tạo trong Product Service sẽ xuất hiện tại đây."
        }
        emptyAction={
          hasFilters ? (
            <Button variant="outline" size="sm" onClick={resetFilters}>
              Xoá bộ lọc
            </Button>
          ) : (
            <Button variant="outline" size="sm" render={<Link href="/admin/products/new" />}>
              Tạo sản phẩm
            </Button>
          )
        }
        page={productsQuery.page}
        hasPrevious={productsQuery.hasPrevious}
        hasNext={productsQuery.hasNext}
        onPrevious={productsQuery.goPrevious}
        onNext={productsQuery.goNext}
        renderMobileCard={(product) => (
          <Link
            href={`/admin/products/${product.product_id}`}
            className="focus-ring block space-y-4 rounded-lg"
          >
            <div className="flex items-start justify-between gap-4">
              <div className="min-w-0 space-y-1">
                <h2 className="truncate text-headline-6 font-serif font-normal">
                  {product.name}
                </h2>
                <p className="truncate font-mono text-caption text-muted-foreground">
                  {product.slug}
                </p>
              </div>
              <StatusBadge value={product.status} labels={PRODUCT_STATUS_LABELS} />
            </div>
            <dl className="grid grid-cols-2 gap-x-4 gap-y-3 text-[14px]">
              <div className="space-y-1">
                <dt className="text-caption text-muted-foreground">Danh mục</dt>
                <dd>
                  {categoryName(categoryById, product.category_id) ?? (
                    <IdChip value={product.category_id} label="Sao chép mã danh mục" />
                  )}
                </dd>
              </div>
              <div className="space-y-1 text-right">
                <dt className="text-caption text-muted-foreground">Phiên bản</dt>
                <dd className="font-mono tabular-nums">{product.catalog_revision}</dd>
              </div>
              <div className="col-span-2 space-y-1">
                <dt className="text-caption text-muted-foreground">Cập nhật</dt>
                <dd>
                  <time dateTime={product.updated_at}>{formatDateTime(product.updated_at)}</time>
                </dd>
              </div>
            </dl>
          </Link>
        )}
      />
    </div>
  );
}
