"use client";

import { useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Search, X } from "lucide-react";

import { IdChip } from "@/components/admin/id-chip";
import { ProductName } from "@/components/admin/promotions/promotion-common";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { searchApi } from "@/lib/api/search";
import { isUuid } from "@/lib/promotion/rule";

interface ProductPickerProps {
  value: string;
  onChange: (productId: string) => void;
  disabled?: boolean;
  error?: string;
}

/**
 * Chọn sản phẩm cho markdown. Tìm theo tên qua Search v2 (chỉ thấy sản phẩm ACTIVE đã
 * được lập chỉ mục); sản phẩm nháp hoặc chưa lập chỉ mục vẫn chọn được bằng cách dán
 * mã sản phẩm — Promotion kiểm tra lại với Product khi lưu.
 */
export function ProductPicker({ value, onChange, disabled, error }: ProductPickerProps) {
  const [term, setTerm] = useState("");
  const [debounced, setDebounced] = useState("");

  useEffect(() => {
    const timer = window.setTimeout(() => setDebounced(term.trim()), 300);
    return () => window.clearTimeout(timer);
  }, [term]);

  const search = useQuery({
    queryKey: ["admin", "product-picker", debounced],
    queryFn: ({ signal }) => searchApi.search({ q: debounced, size: 8, in_stock: false }, signal),
    enabled: debounced.length >= 2 && !value,
    staleTime: 30_000,
  });

  if (value && isUuid(value)) {
    return (
      <div className="space-y-2">
        <Label>Sản phẩm *</Label>
        <div className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-border bg-surface-2 p-3">
          <div className="min-w-0 space-y-1">
            <p className="truncate text-[15px]"><ProductName productId={value} /></p>
            <IdChip value={value} label="Sao chép mã sản phẩm" />
          </div>
          {disabled ? null : (
            <Button type="button" variant="ghost" size="sm" onClick={() => onChange("")}>
              <X aria-hidden />
              Đổi sản phẩm
            </Button>
          )}
        </div>
        {error ? <p role="alert" className="text-caption text-danger">{error}</p> : null}
      </div>
    );
  }

  const items = search.data?.items ?? [];
  return (
    <div className="space-y-4">
      <div className="space-y-2">
        <Label htmlFor="markdown-product-search">Tìm sản phẩm theo tên *</Label>
        <div className="relative">
          <Search className="pointer-events-none absolute left-3.5 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" aria-hidden />
          <Input
            id="markdown-product-search"
            value={term}
            disabled={disabled}
            autoComplete="off"
            className="pl-10"
            placeholder="Nhập ít nhất 2 ký tự"
            onChange={(event) => setTerm(event.target.value)}
          />
        </div>
        <div aria-live="polite" className="space-y-1">
          {search.isFetching ? <p className="text-caption text-muted-foreground">Đang tìm…</p> : null}
          {search.isError ? (
            <p role="alert" className="text-caption text-danger">
              Chưa tìm được sản phẩm. Hãy thử lại hoặc dán mã sản phẩm bên dưới.
            </p>
          ) : null}
          {debounced.length >= 2 && !search.isFetching && !search.isError && items.length === 0 ? (
            <p className="text-caption text-muted-foreground">Không có sản phẩm đang bán nào khớp. Sản phẩm nháp hoặc chưa lập chỉ mục cần dán mã sản phẩm.</p>
          ) : null}
        </div>
        {items.length > 0 ? (
          <ul className="divide-y divide-border overflow-hidden rounded-lg border border-border bg-surface-overlay">
            {items.map((item) => (
              <li key={item.product_id}>
                <button
                  type="button"
                  className="focus-ring flex min-h-11 w-full items-center justify-between gap-3 px-4 py-2.5 text-left text-[14px] transition-colors duration-200 hover:bg-surface-2"
                  onClick={() => {
                    onChange(item.product_id);
                    setTerm("");
                  }}
                >
                  <span className="min-w-0 truncate font-medium">{item.name}</span>
                  <span className="shrink-0 font-mono text-caption text-muted-foreground">{item.product_id.slice(0, 8)}…</span>
                </button>
              </li>
            ))}
          </ul>
        ) : null}
      </div>

      <div className="space-y-2">
        <Label htmlFor="markdown-product-id">Hoặc dán mã sản phẩm (UUID)</Label>
        <Input
          id="markdown-product-id"
          value={value}
          disabled={disabled}
          autoComplete="off"
          aria-invalid={error ? true : undefined}
          placeholder="0199aaaa-bbbb-7ccc-8ddd-eeeeffff0000"
          className="font-mono"
          onChange={(event) => onChange(event.target.value.trim())}
        />
      </div>
      {error ? <p role="alert" className="text-caption text-danger">{error}</p> : null}
    </div>
  );
}
