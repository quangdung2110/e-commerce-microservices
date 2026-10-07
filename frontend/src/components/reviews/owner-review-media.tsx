import { StorefrontImage } from "@/components/catalog/storefront-image";
import type { OwnerReview } from "@/lib/api/review";

/** Ảnh/video của review do chính tác giả xem; URL ký sống ngắn nên không cache. */
export function OwnerReviewMedia({ media }: { media: OwnerReview["media"] }) {
  if (media.length === 0) return null;
  return (
    <ul className="grid grid-cols-2 gap-3 sm:grid-cols-3" aria-label="Ảnh và video của đánh giá">
      {media.toSorted((a, b) => a.sort_order - b.sort_order).map((item, index) => (
        <li key={item.asset_id} className="aspect-square overflow-hidden rounded-xl border border-border bg-surface-2">
          {item.media_type === "VIDEO" ? (
            <video src={item.url} controls preload="metadata" aria-label={`Video ${index + 1} của đánh giá`} className="size-full object-contain" />
          ) : (
            <StorefrontImage src={item.url} alt={`Ảnh ${index + 1} của đánh giá`} className="size-full object-contain" />
          )}
        </li>
      ))}
    </ul>
  );
}
