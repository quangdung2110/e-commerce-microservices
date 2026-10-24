"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useRef, useState, type FormEvent } from "react";
import { ArrowLeft, LoaderCircle } from "lucide-react";

import { MediaUploader } from "@/components/reviews/media-uploader";
import { ProductSnapshot } from "@/components/reviews/product-snapshot";
import { ReviewFormFields } from "@/components/reviews/review-form-fields";
import { Alert } from "@/components/ui/alert";
import { Button, buttonVariants } from "@/components/ui/button";
import { Card, CardContent, CardFooter } from "@/components/ui/card";
import { ErrorState } from "@/components/ui/error-state";
import { Skeleton } from "@/components/ui/skeleton";
import { useToast } from "@/components/ui/toast";
import { useReviewMediaUploader } from "@/hooks/use-review-media-uploader";
import { useCreateReview, useEligibilityStatus, usePendingReviews } from "@/hooks/use-reviews";
import { problemCode, problemStatus } from "@/lib/api/problem";
import { normalizeReviewContent, reviewExpiryNote, REVIEW_CONTENT_MAX } from "@/lib/review";
import { cn } from "@/lib/utils";

export function ReviewCreateContent({ eligibilityId }: { eligibilityId: string }) {
  const status = useEligibilityStatus(eligibilityId);

  return (
    <main className="mx-auto w-full max-w-small space-y-8 px-6 py-10 md:px-8 md:py-14">
      <BackLink />
      <header className="space-y-2">
        <h1 className="font-serif text-headline-2 font-normal">Viết đánh giá</h1>
        <p className="max-w-prose-narrow text-[15px] text-muted-foreground">
          Mỗi sản phẩm đã mua được đánh giá một lần và chỉnh sửa tối đa một lần sau đó.
        </p>
      </header>

      {!eligibilityId ? (
        <Alert variant="warning" title="Chưa chọn sản phẩm để đánh giá">
          Hãy chọn một sản phẩm trong danh sách chờ đánh giá.
        </Alert>
      ) : status.isPending ? (
        <div aria-busy="true" aria-label="Đang kiểm tra quyền đánh giá" className="space-y-4">
          <Skeleton className="h-24 w-full rounded-xl" />
          <Skeleton className="h-64 w-full rounded-xl" />
        </div>
      ) : status.isError ? (
        <ErrorState
          error={status.error}
          // 403/404 là câu trả lời dứt khoát: thử lại không đổi được kết quả.
          onRetry={[403, 404].includes(problemStatus(status.error) ?? 0) ? undefined : () => void status.refetch()}
        />
      ) : status.data.status === "CLAIMED" ? (
        <Alert variant="info" title="Bạn đã đánh giá sản phẩm này">
          <span className="flex flex-wrap items-center gap-3">
            Mỗi sản phẩm chỉ được đánh giá một lần.
            {status.data.review_id ? (
              <Link href={`/reviews/${encodeURIComponent(status.data.review_id)}`} className="focus-ring rounded-md font-medium underline underline-offset-2">
                Xem đánh giá của bạn
              </Link>
            ) : null}
          </span>
        </Alert>
      ) : status.data.status === "EXPIRED" ? (
        <Alert variant="warning" title="Đã hết thời hạn đánh giá">
          Sản phẩm này không còn nhận đánh giá nữa.
        </Alert>
      ) : (
        <CreateForm key={eligibilityId} eligibilityId={eligibilityId} expiresAt={status.data.expires_at} />
      )}
    </main>
  );
}

function CreateForm({ eligibilityId, expiresAt }: { eligibilityId: string; expiresAt: string }) {
  const router = useRouter();
  const { addToast } = useToast();
  const active = useRef(true);
  useEffect(() => {
    active.current = true;
    return () => { active.current = false; };
  }, []);
  const create = useCreateReview((saved) => {
    if (!active.current) return;
    addToast({ type: "success", title: "Đã gửi đánh giá", message: "Cảm ơn bạn đã chia sẻ trải nghiệm." });
    router.replace(`/reviews/${encodeURIComponent(saved.review_id)}`);
  });
  const uploader = useReviewMediaUploader();

  // Tên và ảnh sản phẩm chỉ có trong danh sách chờ; quét tối đa vài trang để tìm đúng mục.
  const pending = usePendingReviews({ scanAll: true });
  const snapshot = pending.items.find((item) => item.eligibility_id === eligibilityId);
  const searchingSnapshot = !snapshot && !pending.isError && (pending.isPending || !pending.scanned);

  const [rating, setRating] = useState(0);
  const [content, setContent] = useState("");
  const [ratingError, setRatingError] = useState<string | null>(null);
  const [contentError, setContentError] = useState<string | null>(null);
  const [mediaError, setMediaError] = useState<string | null>(null);

  const dirty = rating > 0 || content.length > 0 || uploader.items.length > 0;
  const submitted = create.isSuccess;
  useEffect(() => {
    if (!dirty || submitted) return;
    const warn = (event: BeforeUnloadEvent) => event.preventDefault();
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [dirty, submitted]);

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setMediaError(null);

    const normalized = normalizeReviewContent(content);
    const ratingProblem = rating === 0 ? "Hãy chọn số sao." : null;
    const contentProblem = content.length > REVIEW_CONTENT_MAX ? `Nhận xét tối đa ${REVIEW_CONTENT_MAX} ký tự.` : null;
    setRatingError(ratingProblem);
    setContentError(contentProblem);
    if (ratingProblem || contentProblem) return;

    if (uploader.isUploading) {
      setMediaError("Hãy chờ các tệp tải lên xong.");
      return;
    }
    if (uploader.hasError) {
      setMediaError("Có tệp chưa tải lên được. Hãy thử lại hoặc gỡ tệp đó trước khi gửi.");
      return;
    }
    if (!uploader.ensureFresh()) {
      setMediaError("Một số tệp đã hết hạn gắn vào đánh giá. Hãy tải chúng lên lại.");
      return;
    }

    create.mutate(
      {
        eligibility_id: eligibilityId,
        rating,
        ...(normalized ? { content: normalized } : {}),
        ...(uploader.assetIds.length ? { media_asset_ids: uploader.assetIds } : {}),
      }
    );
  }

  const expiry = reviewExpiryNote(expiresAt);
  const code = create.isError ? problemCode(create.error) : null;
  const mediaRejected = code === "MEDIA_NOT_VERIFIED" || code === "INVALID_MEDIA_REFERENCE";

  return (
    <form onSubmit={submit} noValidate className="space-y-6">
      <Card>
        <CardContent className="space-y-3">
          {snapshot ? (
            <ProductSnapshot name={snapshot.product_name} imageUrl={snapshot.product_image_url} variantName={snapshot.variant_name} />
          ) : searchingSnapshot ? (
            <Skeleton className="h-20 w-full rounded-xl" aria-label="Đang tải thông tin sản phẩm" />
          ) : (
            <ProductSnapshot name={null} imageUrl={null} variantName={null} />
          )}
          <p className={cn("text-caption text-muted-foreground", expiry.urgent && "font-medium text-warning")}>{expiry.text}</p>
        </CardContent>
      </Card>

      <Card>
        <CardContent className="space-y-6">
          <ReviewFormFields
            rating={rating}
            onRatingChange={(value) => { setRating(value); setRatingError(null); }}
            content={content}
            onContentChange={(value) => { setContent(value); setContentError(null); }}
            disabled={create.isPending}
            ratingError={ratingError}
            contentError={contentError}
          />

          <div className="space-y-3">
            <p className="text-[14px] font-medium">
              Ảnh hoặc video <span className="font-normal text-muted-foreground">(không bắt buộc)</span>
            </p>
            <MediaUploader
              items={uploader.items}
              onAdd={uploader.add}
              onRemove={uploader.remove}
              onRetry={uploader.retry}
              disabled={create.isPending}
            />
            {mediaError ? <p role="alert" className="text-caption text-danger">{mediaError}</p> : null}
          </div>
        </CardContent>

        <CardFooter className="flex-col items-stretch gap-4">
          {create.isError ? (
            <ErrorState
              error={create.error}
              message={mediaRejected ? "Ảnh hoặc video chưa dùng được. Hãy gỡ các tệp đó rồi thêm lại trước khi gửi." : undefined}
            />
          ) : null}
          <div className="flex flex-col-reverse gap-3 sm:flex-row sm:justify-end">
            <Link href="/reviews" className={cn(buttonVariants({ variant: "outline" }))}>Để sau</Link>
            <Button type="submit" variant="clay" disabled={create.isPending || uploader.isUploading}>
              {create.isPending ? <LoaderCircle className="animate-spin" aria-hidden /> : null}
              {create.isPending ? "Đang gửi…" : "Gửi đánh giá"}
            </Button>
          </div>
        </CardFooter>
      </Card>
    </form>
  );
}

function BackLink() {
  return (
    <Link href="/reviews" className="focus-ring inline-flex min-h-11 items-center gap-2 rounded-lg text-[14px] text-muted-foreground hover:text-foreground">
      <ArrowLeft className="size-4" aria-hidden />
      Đánh giá của tôi
    </Link>
  );
}
