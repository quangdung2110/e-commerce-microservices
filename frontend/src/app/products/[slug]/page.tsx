import { ProductDetailPageContent } from "@/components/product/product-detail-page-content";

interface PageProps {
  params: Promise<{ slug: string }>;
}

export async function generateMetadata({ params }: PageProps) {
  const { slug } = await params;
  // TODO: Fetch product name from API for dynamic title
  return {
    title: `${slug
      .replace(/-/g, " ")
      .replace(/\b\w/g, (c) => c.toUpperCase())} - TechNova`,
    description: `Chi tiết sản phẩm`,
  };
}

export default function ProductDetailPage({ params }: PageProps) {
  return <ProductDetailPageContent params={params} />;
}
