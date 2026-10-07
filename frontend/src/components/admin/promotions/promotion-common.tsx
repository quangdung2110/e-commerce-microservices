"use client";

import { useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";

import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { productApi } from "@/lib/api/product";

/** Đồng hồ trình duyệt, chỉ để gợi ý trên UI; backend mới là nơi quyết định theo đồng hồ của nó. */
export function useNowMs(): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 30_000);
    return () => window.clearInterval(timer);
  }, []);
  return now;
}

export function StaleAlert({ onReload }: { onReload: () => void | Promise<unknown> }) {
  return (
    <Alert variant="warning" title="Campaign vừa được người khác thay đổi">
      Tải lại bản mới trước khi tiếp tục; hệ thống sẽ không tự ghi đè thay đổi của người khác.
      <div className="mt-3">
        <Button type="button" variant="outline" size="sm" onClick={() => void onReload()}>
          Tải lại dữ liệu
        </Button>
      </div>
    </Alert>
  );
}

/** Tên sản phẩm cho một `product_id`; lỗi thì rơi về hiển thị mã, không chặn màn hình. */
export function ProductName({ productId }: { productId: string }) {
  const query = useQuery({
    queryKey: ["admin", "product-name", productId],
    queryFn: ({ signal }) => productApi.getProduct(productId, signal),
    staleTime: 5 * 60_000,
    retry: false,
  });
  if (query.isPending) return <span className="text-muted-foreground">Đang tải tên…</span>;
  if (query.isError) return <span className="text-muted-foreground">Không đọc được tên sản phẩm</span>;
  return <span className="font-medium">{query.data.data.name}</span>;
}
