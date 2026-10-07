import { ReviewsPageContent, type ReviewsTab } from "@/components/reviews/reviews-page-content";

export const metadata = {
  title: "Đánh giá của tôi - TechNova",
  description: "Sản phẩm chờ đánh giá và những đánh giá bạn đã viết",
};

export default async function ReviewsPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const params = await searchParams;
  const tab: ReviewsTab = params.tab === "written" ? "written" : "pending";
  return <ReviewsPageContent tab={tab} />;
}
