import { ProductDetail } from "@/components/admin/products/product-detail";

export default async function AdminProductDetailPage({
  params,
}: {
  params: Promise<{ productId: string }>;
}) {
  const { productId } = await params;
  return <ProductDetail productId={productId} />;
}
