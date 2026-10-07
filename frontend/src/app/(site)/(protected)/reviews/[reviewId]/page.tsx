import { ReviewDetailContent } from "@/components/reviews/review-detail-content";

export const metadata = {
  title: "Đánh giá của bạn - TechNova",
  description: "Xem và chỉnh sửa đánh giá bạn đã viết",
};

export default async function ReviewDetailPage({ params }: { params: Promise<{ reviewId: string }> }) {
  const { reviewId } = await params;
  return <ReviewDetailContent reviewId={reviewId} />;
}
