"use client";

import { useEffect, useState } from "react";
import { ChevronRight, Home } from "lucide-react";
import { useProductDetail } from "@/hooks/use-product-detail";
import { productApi } from "@/services/product";
import { Gallery } from "./gallery";
import { ProductInfo } from "./product-info";
import { DescriptionTabs } from "./description-tabs";
import { RelatedProducts } from "./related-products";
import { Separator } from "@/components/ui/separator";

// ─── Component ──────────────────────────────────────────────────

interface PDPContentProps {
  params: Promise<{ slug: string }>;
}

export function ProductDetailPageContent({ params }: PDPContentProps) {
  const [slug, setSlug] = useState<string | null>(null);
  const [productId, setProductId] = useState<string | null>(null);
  const [isResolving, setIsResolving] = useState(true);

  // Resolve slug to product_id
  useEffect(() => {
    params.then((p) => setSlug(p.slug));
  }, [params]);

  useEffect(() => {
    if (!slug) return;
    setIsResolving(true);
    productApi
      .getBySlug(slug)
      .then((data) => {
        if (data.items && data.items.length > 0) {
          setProductId(data.items[0].product_id);
        } else {
          setProductId("__not_found__");
        }
      })
      .catch(() => setProductId("__not_found__"))
      .finally(() => setIsResolving(false));
  }, [slug]);

  const { data: detail, isLoading } = useProductDetail(productId ?? null);

  // Not found state
  if (!isResolving && productId === "__not_found__") {
    return (
      <div className="flex-1 container mx-auto px-4 py-8 max-w-6xl">
        <div className="flex flex-col items-center justify-center py-20 text-center">
          <h1 className="text-4xl font-black text-foreground mb-4">
            404
          </h1>
          <p className="text-lg text-muted-foreground mb-6">
            Không tìm thấy sản phẩm này.
          </p>
          <a
            href="/"
            className="inline-flex items-center gap-2 text-primary hover:underline font-medium"
          >
            Quay về trang chủ
          </a>
        </div>
      </div>
    );
  }

  // Collect images from product data
  const images = [
    // Use product thumbnail
    ...(detail?.product?.thumbnail_url
      ? [
          {
            url: detail.product.thumbnail_url,
            thumbnail_url: detail.product.thumbnail_url,
          },
        ]
      : []),
    // Use SKU images
    ...(detail?.skus
      ?.filter((s) => s.image_url)
      .map((s) => ({
        url: s.image_url!,
        thumbnail_url: s.image_url,
      })) || []),
  ];

  // Get category info for related products
  const categoryId = null; // TODO: from product data

  return (
    <div className="flex-1 container mx-auto px-4 py-8 max-w-6xl relative z-10">
      {/* Breadcrumb */}
      <div className="flex items-center gap-2 text-sm text-white/50 font-mono mb-8">
        <Home className="h-4 w-4" />
        <ChevronRight className="h-3 w-3 opacity-50" />
        <a href="/" className="hover:text-white transition-colors">
          Trang chủ
        </a>
        <ChevronRight className="h-3 w-3 opacity-50" />
        <span className="font-medium text-white/90">
          {detail?.product?.name || slug || "Sản phẩm"}
        </span>
      </div>

      {/* Product Main Section */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-8 lg:gap-12 mb-12">
        {/* Gallery */}
        <Gallery
          images={images.length > 0 ? images : []}
          productName={detail?.product?.name || "Sản phẩm"}
          isLoading={isLoading || isResolving}
        />

        {/* Product Info */}
        <ProductInfo productId={productId ?? ""} />
      </div>

      <Separator className="my-8 bg-white/10" />

      {/* Description & Specs */}
      <div className="mb-12">
        <DescriptionTabs
          description={detail?.product?.name}
          attributes={null}
          isLoading={isLoading}
        />
      </div>

      {/* Related Products */}
      {categoryId && productId && (
        <RelatedProducts
          categoryId={categoryId}
          excludeProductId={productId}
          productName={detail?.product?.name}
        />
      )}
    </div>
  );
}
