import { ReviewCreateContent } from "@/components/reviews/review-create-content";

export const metadata = {
  title: "Viết đánh giá - TechNova",
  description: "Chia sẻ trải nghiệm về sản phẩm bạn đã mua",
};

export default async function NewReviewPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const params = await searchParams;
  const eligibility = typeof params.eligibility === "string" ? params.eligibility.trim() : "";
  return <ReviewCreateContent key={eligibility} eligibilityId={eligibility} />;
}
