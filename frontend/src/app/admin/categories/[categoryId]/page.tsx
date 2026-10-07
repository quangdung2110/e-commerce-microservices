import { CategoryDetail } from "@/components/admin/categories/category-detail";

export default async function AdminCategoryDetailPage({
  params,
}: {
  params: Promise<{ categoryId: string }>;
}) {
  const { categoryId } = await params;
  return <CategoryDetail categoryId={categoryId} />;
}
