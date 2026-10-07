"use client";

import { useId } from "react";

import { RatingInput } from "@/components/reviews/rating-stars";
import { Textarea } from "@/components/ui/textarea";
import { REVIEW_CONTENT_MAX } from "@/lib/review";

interface ReviewFormFieldsProps {
  rating: number;
  onRatingChange: (value: number) => void;
  content: string;
  onContentChange: (value: string) => void;
  disabled?: boolean;
  ratingError?: string | null;
  contentError?: string | null;
}

/** Hai ô dùng chung cho viết mới và sửa: điểm sao (bắt buộc) và nội dung (không bắt buộc). */
export function ReviewFormFields({
  rating,
  onRatingChange,
  content,
  onContentChange,
  disabled,
  ratingError,
  contentError,
}: ReviewFormFieldsProps) {
  const id = useId();
  const ratingLabel = `${id}-rating-label`;
  const ratingErrorId = `${id}-rating-error`;
  const contentId = `${id}-content`;
  const contentErrorId = `${id}-content-error`;
  const counterId = `${id}-counter`;

  return (
    <>
      <div className="space-y-2">
        <p id={ratingLabel} className="text-[14px] font-medium">
          Bạn thấy sản phẩm thế nào? <span className="text-danger" aria-hidden>*</span>
        </p>
        <RatingInput
          value={rating}
          onChange={onRatingChange}
          disabled={disabled}
          invalid={Boolean(ratingError)}
          aria-labelledby={ratingLabel}
          aria-describedby={ratingError ? ratingErrorId : undefined}
        />
        {ratingError ? <p id={ratingErrorId} role="alert" className="text-caption text-danger">{ratingError}</p> : null}
      </div>

      <div className="space-y-2">
        <label htmlFor={contentId} className="text-[14px] font-medium">
          Nhận xét <span className="font-normal text-muted-foreground">(không bắt buộc)</span>
        </label>
        <Textarea
          id={contentId}
          value={content}
          onChange={(event) => onContentChange(event.target.value)}
          disabled={disabled}
          rows={6}
          placeholder="Chất lượng, cảm nhận khi dùng, điều bạn muốn người mua sau biết…"
          aria-invalid={Boolean(contentError) || undefined}
          aria-describedby={[counterId, contentError ? contentErrorId : null].filter(Boolean).join(" ")}
        />
        <div className="flex justify-between gap-4">
          {contentError ? <p id={contentErrorId} role="alert" className="text-caption text-danger">{contentError}</p> : <span />}
          <p id={counterId} className="text-caption tabular-nums text-muted-foreground">
            {content.length}/{REVIEW_CONTENT_MAX}
          </p>
        </div>
      </div>
    </>
  );
}
