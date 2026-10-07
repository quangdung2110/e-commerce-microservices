import { Suspense } from "react";

import { ProductList } from "@/components/admin/products/product-list";
import { ProductListSkeleton } from "@/components/admin/products/product-list-skeleton";

export default function AdminProductsPage() {
  return (
    <Suspense fallback={<ProductListSkeleton />}>
      <ProductList />
    </Suspense>
  );
}
