"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { ArrowLeft, LoaderCircle, MessageSquareReply, PenLine, RotateCw } from "lucide-react";

import { StatusBadge } from "@/components/admin/status-badge";
import { MediaUploader } from "@/components/reviews/media-uploader";
import { OwnerReviewMedia } from "@/components/reviews/owner-review-media";
import { ProductSnapshot } from "@/components/reviews/product-snapshot";
import { RatingStars } from "@/components/reviews/rating-stars";
import { ReviewFormFields } from "@/components/reviews/review-form-fields";
import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardFooter } from "@/components/ui/card";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { ErrorState } from "@/components/ui/error-state";
import { Skeleton } from "@/components/ui/skeleton";
import { useToast } from "@/components/ui/toast";
import { useReviewMediaUploader } from "@/hooks/use-review-media-uploader";
import { useMyReview, usePatchReview } from "@/hooks/use-reviews";
import { problemCode, problemStatus } from "@/lib/api/problem";
import type { OwnerReview, PatchReviewRequest } from "@/lib/api/review";
import { formatDateTime } from "@/lib/format";
import { REVIEW_VISIBILITY_LABELS, REVIEW_VISIBILITY_NOTES } from "@/lib/labels/review";
import { normalizeReviewContent, REVIEW_CONTENT_MAX } from "@/lib/review";

export function ReviewDetailContent({ reviewId }: { reviewId: string }) {
  const query = useMyReview(reviewId);
  const [editing, setEditing] = useState(false);

  return (
    <main className="mx-auto w-full max-w-small space-y-8 px-6 py-10 md:px-8 md:py-14">
      <Link
        href="/reviews?tab=written"
        className="focus-ring inline-flex min-h-11 items-center gap-2 rounded-lg text-[14px] text-muted-foreground hover:text-foreground"
      >
        <ArrowLeft className="size-4" aria-hidden />
        Đánh giá đã viết
      </Link>

      {query.isPending ? (
        <div aria-busy="true" aria-label="Đang tải đánh giá" className="space-y-6">
          <Skeleton className="h-10 w-64" />
          <Skeleton className="h-24 w-full rounded-xl" />
          <Skeleton className="h-48 w-full rounded-xl" />
        </div>
      ) : query.isError && !query.data ? (
        <ErrorState
          error={query.error}
          message={problemCode(query.error) === "NOT_REVIEW_OWNER" ? "Bạn không có quyền xem đánh giá này." : undefined}
          onRetry={[403, 404].includes(problemStatus(query.error) ?? 0) ? undefined : () => void query.refetch()}
        />
      ) : query.data ? (
        editing ? (
          <ReviewEditForm review={query.data} onDone={() => setEditing(false)} onStale={() => void query.refetch()} />
        ) : (
          <ReviewView review={query.data} refetching={query.isFetching} onRefresh={() => void query.refetch()} onEdit={() => setEditing(true)} />
        )
      ) : null}
    </main>
  );
}

function ReviewView({
  review,
  refetching,
  onRefresh,
  onEdit,
}: {
  review: OwnerReview;
  refetching: boolean;
  onRefresh: () => void;
  onEdit: () => void;
}) {
  const note = REVIEW_VISIBILITY_NOTES[review.visibility_status];
  const editable = review.visibility_status === "APPROVED" && review.edit_count === 0;

  return (
    <>
      <header className="flex flex-col justify-between gap-4 sm:flex-row sm:items-start">
        <div className="space-y-2">
          <h1 className="font-serif text-headline-2 font-normal">Đánh giá của bạn</h1>
          <p className="text-[15px] text-muted-foreground">
            Gửi lúc {formatDateTime(review.created_at)}
            {review.edit_count > 0
              ? review.content_edited_at
                ? ` · Đã chỉnh sửa lúc ${formatDateTime(review.content_edited_at)}`
                : " · Đã chỉnh sửa"
              : ""}
          </p>
        </div>
        <StatusBadge value={review.visibility_status} labels={REVIEW_VISIBILITY_LABELS} />
      </header>

      {note ? (
        <Alert variant={review.visibility_status === "REMOVED" ? "danger" : "warning"} title={REVIEW_VISIBILITY_LABELS[review.visibility_status].text}>
          {note}
        </Alert>
      ) : null}

      <Card>
        <CardContent className="space-y-5">
          <ProductSnapshot name={review.product_name} imageUrl={review.product_image_url} variantName={review.variant_name} />
          <div className="space-y-3 border-t border-border pt-5">
            <RatingStars value={review.rating} />
            {review.content ? (
              <p className="max-w-prose-narrow whitespace-pre-line break-words text-[15px]">{review.content}</p>
            ) : (
              <p className="text-[15px] text-muted-foreground">Không có nội dung, chỉ có điểm sao.</p>
            )}
          </div>

          {review.visibility_status === "APPROVED" ? (
            <>
              <OwnerReviewMedia media={review.media} />
              {review.has_media && review.media.length === 0 ? (
                <p className="text-caption text-muted-foreground">Không tải được ảnh hoặc video lúc này.</p>
              ) : null}
            </>
          ) : review.has_media ? (
            <p className="text-caption text-muted-foreground">Ảnh và video không hiển thị khi đánh giá không còn công khai.</p>
          ) : null}
        </CardContent>

        <CardFooter className="flex-col items-stretch gap-3 sm:flex-row sm:items-center sm:justify-between">
          <p className="text-caption text-muted-foreground">
            {editable
              ? "Bạn còn 1 lần chỉnh sửa."
              : review.edit_count > 0
                ? "Bạn đã dùng lượt chỉnh sửa duy nhất."
                : "Đánh giá này không thể chỉnh sửa."}
          </p>
          <div className="flex flex-col gap-3 sm:flex-row">
            <Button variant="ghost" disabled={refetching} onClick={onRefresh}>
              <RotateCw className={refetching ? "animate-spin" : undefined} aria-hidden />
              Tải lại
            </Button>
            {editable ? (
              <Button variant="outline" onClick={onEdit}>
                <PenLine aria-hidden />
                Chỉnh sửa
              </Button>
            ) : null}
          </div>
        </CardFooter>
      </Card>

      {review.admin_reply ? (
        <aside className="space-y-2 rounded-xl border border-border bg-surface-2 p-6" aria-label="Phản hồi từ cửa hàng">
          <p className="flex items-center gap-2 font-medium">
            <MessageSquareReply className="size-4" aria-hidden />
            Phản hồi từ cửa hàng
          </p>
          <p className="whitespace-pre-line break-words">{review.admin_reply.content}</p>
          <time dateTime={review.admin_reply.updated_at} className="text-caption text-muted-foreground">
            {formatDateTime(review.admin_reply.updated_at)}
          </time>
        </aside>
      ) : null}
    </>
  );
}

type MediaMode = "keep" | "replace";

function ReviewEditForm({
  review,
  onDone,
  onStale,
}: {
  review: OwnerReview;
  onDone: () => void;
  onStale: () => void;
}) {
  const { addToast } = useToast();
  const patch = usePatchReview(review.review_id);
  const uploader = useReviewMediaUploader();

  const [rating, setRating] = useState(review.rating);
  const [content, setContent] = useState(review.content ?? "");
  const [mediaMode, setMediaMode] = useState<MediaMode>("keep");
  const [ratingError, setRatingError] = useState<string | null>(null);
  const [contentError, setContentError] = useState<string | null>(null);
  const [mediaError, setMediaError] = useState<string | null>(null);
  const [confirming, setConfirming] = useState(false);

  const normalized = normalizeReviewContent(content);
  const ratingChanged = rating !== review.rating;
  const contentChanged = normalized !== review.content;
  const mediaChanged = mediaMode === "replace";
  const changed = ratingChanged || contentChanged || mediaChanged;

  const submitting = patch.isPending;
  useEffect(() => {
    if (!changed || patch.isSuccess) return;
    const warn = (event: BeforeUnloadEvent) => event.preventDefault();
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [changed, patch.isSuccess]);

  function validateAndAsk() {
    setMediaError(null);
    const contentProblem = content.length > REVIEW_CONTENT_MAX ? `Nhận xét tối đa ${REVIEW_CONTENT_MAX} ký tự.` : null;
    setContentError(contentProblem);
    setRatingError(rating === 0 ? "Hãy chọn số sao." : null);
    if (contentProblem || rating === 0) return;

    if (mediaChanged) {
      if (uploader.isUploading) return setMediaError("Hãy chờ các tệp tải lên xong.");
      if (uploader.hasError) return setMediaError("Có tệp chưa tải lên được. Hãy thử lại hoặc gỡ tệp đó.");
      if (!uploader.ensureFresh()) return setMediaError("Một số tệp đã hết hạn gắn vào đánh giá. Hãy tải chúng lên lại.");
    }
    setConfirming(true);
  }

  function save() {
    // Chỉ gửi phần đổi: field vắng mặt được giữ nguyên; `content: null` là xoá nội dung;
    // `media_asset_ids` là thay toàn bộ tệp (mảng rỗng = gỡ hết).
    const body: PatchReviewRequest = {};
    if (ratingChanged) body.rating = rating;
    if (contentChanged) body.content = normalized;
    if (mediaChanged) body.media_asset_ids = uploader.assetIds;

    patch.mutate(body, {
      onSuccess: () => {
        setConfirming(false);
        addToast({ type: "success", title: "Đã lưu chỉnh sửa", message: "Bạn đã dùng lượt chỉnh sửa duy nhất của đánh giá này." });
        onDone();
      },
      onError: () => {
        setConfirming(false);
        // Có thể đánh giá vừa bị ẩn hoặc lượt sửa đã dùng ở nơi khác: lấy lại bản hiện tại.
        onStale();
      },
    });
  }

  return (
    <>
      <header className="space-y-2">
        <h1 className="font-serif text-headline-2 font-normal">Chỉnh sửa đánh giá</h1>
        <p className="max-w-prose-narrow text-[15px] text-muted-foreground">
          Bạn chỉ chỉnh sửa được một lần. Hãy kiểm tra kỹ trước khi lưu.
        </p>
      </header>

      <Card>
        <CardContent className="space-y-6">
          <ProductSnapshot name={review.product_name} imageUrl={review.product_image_url} variantName={review.variant_name} />

          <div className="space-y-6 border-t border-border pt-6">
            <ReviewFormFields
              rating={rating}
              onRatingChange={(value) => { setRating(value); setRatingError(null); }}
              content={content}
              onContentChange={(value) => { setContent(value); setContentError(null); }}
              disabled={submitting}
              ratingError={ratingError}
              contentError={contentError}
            />

            <div className="space-y-3">
              <p className="text-[14px] font-medium">Ảnh hoặc video</p>
              {mediaMode === "keep" ? (
                <div className="space-y-3">
                  {review.media.length > 0 ? (
                    <OwnerReviewMedia media={review.media} />
                  ) : (
                    <p className="text-[14px] text-muted-foreground">
                      {review.has_media ? "Đang giữ nguyên các tệp hiện tại." : "Đánh giá này chưa có ảnh hoặc video."}
                    </p>
                  )}
                  <Button type="button" variant="outline" size="sm" disabled={submitting} onClick={() => { uploader.reset(); setMediaMode("replace"); }}>
                    Thay hoặc gỡ ảnh/video
                  </Button>
                </div>
              ) : (
                <div className="space-y-3">
                  <Alert variant="warning">
                    Các tệp bạn chọn dưới đây sẽ thay thế toàn bộ ảnh và video hiện tại. Không thể giữ lại một phần; để trống nghĩa là gỡ hết.
                  </Alert>
                  <MediaUploader
                    items={uploader.items}
                    onAdd={uploader.add}
                    onRemove={uploader.remove}
                    onRetry={uploader.retry}
                    disabled={submitting}
                  />
                  <Button type="button" variant="ghost" size="sm" disabled={submitting} onClick={() => { uploader.reset(); setMediaError(null); setMediaMode("keep"); }}>
                    Giữ nguyên tệp hiện tại
                  </Button>
                </div>
              )}
              {mediaError ? <p role="alert" className="text-caption text-danger">{mediaError}</p> : null}
            </div>
          </div>
        </CardContent>

        <CardFooter className="flex-col items-stretch gap-4">
          {patch.isError ? <ErrorState error={patch.error} /> : null}
          <div className="flex flex-col-reverse gap-3 sm:flex-row sm:justify-end">
            <Button type="button" variant="outline" disabled={submitting} onClick={onDone}>Huỷ chỉnh sửa</Button>
            <Button type="button" variant="clay" disabled={!changed || submitting || uploader.isUploading} onClick={validateAndAsk}>
              Lưu thay đổi
            </Button>
          </div>
        </CardFooter>
      </Card>

      <Dialog open={confirming} onOpenChange={(open) => { if (!submitting) setConfirming(open); }}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Lưu chỉnh sửa?</DialogTitle>
            <DialogDescription>
              Mỗi đánh giá chỉ được chỉnh sửa một lần. Sau khi lưu bạn sẽ không sửa được nữa.
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <DialogClose render={<Button type="button" variant="secondary" disabled={submitting} />}>
              Xem lại
            </DialogClose>
            <Button variant="clay" disabled={submitting} onClick={save}>
              {submitting ? <LoaderCircle className="animate-spin" aria-hidden /> : null}
              {submitting ? "Đang lưu…" : "Lưu chỉnh sửa"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}
