"use client";

import { useEffect, useState } from "react";
import Image from "next/image";
import { useRouter, useSearchParams } from "next/navigation";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import type { ColumnDef } from "@tanstack/react-table";
import { DataTable } from "@/components/admin/data-table";
import { PageHeader } from "@/components/admin/page-header";
import { IdChip } from "@/components/admin/id-chip";
import { StatusBadge } from "@/components/admin/status-badge";
import { RatingStars } from "@/components/reviews/rating-stars";
import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { ErrorState } from "@/components/ui/error-state";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Textarea } from "@/components/ui/textarea";
import { useToast } from "@/components/ui/toast";
import { useCursorPager } from "@/hooks/use-cursor-list";
import { useSubmitError } from "@/hooks/use-submit-error";
import { adminReviewApi, type ModerationCase } from "@/lib/api/admin-review";
import { isCommandOutcomeUnknown } from "@/lib/admin-command";
import { REVIEW_VISIBILITY_LABELS } from "@/lib/labels/review";
import { formatDateTime } from "@/lib/format";

const REPORT_REASONS: Record<string, string> = {
  SPAM: "Nội dung rác",
  OFFENSIVE: "Nội dung xúc phạm",
  FAKE: "Đánh giá giả",
  OTHER: "Khác",
};
type Action = "dismiss" | "reply" | "takedown";

export function ModerationQueue() {
  const params = useSearchParams();
  const router = useRouter();
  const cache = useQueryClient();
  const { addToast } = useToast();
  const status =
    params.get("status") === "HIDDEN_BY_REPORT"
      ? "HIDDEN_BY_REPORT"
      : "REPORTED";
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [action, setAction] = useState<Action | null>(null);
  const [content, setContent] = useState("");
  const [legalCase, setLegalCase] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [unresolvedTakedown, setUnresolvedTakedown] = useState<string | null>(
    null,
  );
  const [mediaFailed, setMediaFailed] = useState(false);
  const [now, setNow] = useState(() => Date.now());
  const submitError = useSubmitError();
  useEffect(() => {
    if (!selectedId) return;
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [selectedId]);
  const queue = useCursorPager({
    queryKey: ["admin-reviews", status],
    fetchPage: (cursor, signal) =>
      adminReviewApi.list({ status, cursor, limit: 25 }, signal),
  });
  const selected = queue.data?.items.find(
    (item) => item.review.review_id === selectedId,
  );
  const mutation = useMutation({
    retry: false,
    mutationFn: async (input: {
      action: Action;
      id: string;
      content: string;
      legalCase: string;
    }) => {
      if (input.action === "dismiss") return adminReviewApi.dismiss(input.id);
      if (input.action === "reply")
        return adminReviewApi.reply(input.id, input.content);
      return adminReviewApi.takedown(input.id, {
        legal_case_id: input.legalCase,
        reason: input.content,
      });
    },
    onSuccess: async () => {
      addToast({
        type: "success",
        title:
          action === "reply"
            ? "Đã lưu phản hồi chính thức"
            : action === "dismiss"
              ? "Đã gỡ các báo cáo"
              : "Đã gỡ đánh giá",
      });
      setAction(null);
      setSelectedId(null);
      await cache.invalidateQueries({ queryKey: ["admin-reviews"] });
    },
    onError: (error, input) => {
      submitError.capture(error);
      if (input.action === "takedown" && isCommandOutcomeUnknown(error))
        setUnresolvedTakedown(input.id);
    },
  });
  const uncertainTakedown =
    action === "takedown" && unresolvedTakedown === selectedId;
  function openAction(next: Action) {
    mutation.reset();
    setContent("");
    setLegalCase("");
    setConfirmation("");
    setAction(next);
  }
  function summary(item: ModerationCase) {
    return (
      <div className="space-y-2">
        <RatingStars value={item.review.rating} />
        <p className="line-clamp-3 whitespace-pre-wrap break-words">
          {item.review.content || "Đánh giá chỉ có điểm sao"}
        </p>
        <StatusBadge
          value={item.review.visibility_status}
          labels={REVIEW_VISIBILITY_LABELS}
        />
      </div>
    );
  }
  const columns: ColumnDef<ModerationCase, unknown>[] = [
    {
      id: "review",
      header: "Đánh giá",
      cell: ({ row }) => summary(row.original),
    },
    {
      id: "reports",
      header: "Báo cáo",
      cell: ({ row }) => (
        <>
          <p>{row.original.review.active_report_count} báo cáo</p>
          <p className="text-caption">
            {formatDateTime(row.original.first_reported_at)}
          </p>
        </>
      ),
    },
    {
      id: "action",
      header: "Thao tác",
      cell: ({ row }) => (
        <Button
          variant="outline"
          onClick={() => {
            setSelectedId(row.original.review.review_id);
            setMediaFailed(false);
          }}
        >
          Xem và phân xử
        </Button>
      ),
    },
  ];
  return (
    <div className="space-y-8">
      <PageHeader
        title="Kiểm duyệt đánh giá"
        description="Mỗi đánh giá là một hồ sơ, gồm nội dung, media và các báo cáo. Hồ sơ mở lâu nhất đứng trước."
      />
      <div className="flex flex-wrap gap-2" aria-label="Trạng thái kiểm duyệt">
        {(
          [
            ["REPORTED", "Có báo cáo"],
            ["HIDDEN_BY_REPORT", "Đã tự động ẩn"],
          ] as const
        ).map(([value, label]) => (
          <Button
            key={value}
            variant={status === value ? "secondary" : "ghost"}
            aria-current={status === value ? "page" : undefined}
            onClick={() => {
              setSelectedId(null);
              router.replace(`/admin/reviews?status=${value}`);
            }}
          >
            {label}
          </Button>
        ))}
      </div>
      <DataTable
        columns={columns}
        data={queue.data?.items ?? []}
        getRowId={(item) => item.review.review_id}
        renderMobileCard={(item) => (
          <div className="space-y-4">
            {summary(item)}
            <p className="text-caption">
              {item.review.active_report_count} báo cáo ·{" "}
              {formatDateTime(item.first_reported_at)}
            </p>
            <Button
              variant="outline"
              onClick={() => {
                setSelectedId(item.review.review_id);
                setMediaFailed(false);
              }}
            >
              Xem và phân xử
            </Button>
          </div>
        )}
        caption="Hàng đợi kiểm duyệt đánh giá"
        isLoading={queue.isPending}
        isFetching={queue.isFetching}
        error={!queue.data ? queue.error : undefined}
        partialError={queue.data ? queue.error : undefined}
        onRetry={() => {
          queue.reset();
          void queue.refetch();
        }}
        emptyTitle="Không có đánh giá cần phân xử"
        emptyDescription="Các đánh giá có báo cáo sẽ xuất hiện trong hàng đợi tương ứng."
        emptyAction={
          <Button variant="outline" onClick={() => void queue.refetch()}>
            Tải lại
          </Button>
        }
        page={queue.page}
        hasNext={!queue.isPlaceholderData && queue.hasNext}
        hasPrevious={queue.hasPrevious}
        onNext={queue.goNext}
        onPrevious={queue.goPrevious}
      />
      <Sheet
        open={!!selected && action === null}
        onOpenChange={(open) => {
          if (!open && action === null) setSelectedId(null);
        }}
      >
        <SheetContent side="right" className="w-full sm:w-[min(90vw,640px)]">
          <SheetHeader>
            <SheetTitle>Hồ sơ đánh giá</SheetTitle>
            <SheetDescription>
              Media chỉ dành cho kiểm duyệt, liên kết có thời hạn ngắn.
            </SheetDescription>
          </SheetHeader>
          {selected ? (
            <div className="mt-8 space-y-6">
              <div className="space-y-2">
                <IdChip value={selected.review.review_id} />
                <p className="break-all text-caption">
                  Sản phẩm {selected.review.product_id} · SKU{" "}
                  {selected.review.sku_id}
                </p>
                <RatingStars value={selected.review.rating} />
                <StatusBadge
                  value={selected.review.visibility_status}
                  labels={REVIEW_VISIBILITY_LABELS}
                />
                <p className="whitespace-pre-wrap break-words">
                  {selected.review.content || "Không có nhận xét"}
                </p>
              </div>
              {selected.review.media.length ? (
                <div className="grid grid-cols-2 gap-3">
                  {selected.review.media.map((media) => (
                    <div key={media.asset_id} className="space-y-2">
                      {Date.parse(media.url_expires_at) <= now ? (
                        <p className="text-caption">
                          Liên kết media đã hết hạn.
                        </p>
                      ) : media.media_type === "VIDEO" ? (
                        <video
                          controls
                          preload="metadata"
                          className="w-full rounded-lg"
                          src={media.url}
                          onError={() => setMediaFailed(true)}
                          aria-label="Video đính kèm đánh giá"
                        />
                      ) : (
                        <Image
                          unoptimized
                          width={320}
                          height={320}
                          className="aspect-square w-full rounded-lg object-contain"
                          src={media.url}
                          alt="Ảnh đính kèm đánh giá"
                          onError={() => setMediaFailed(true)}
                        />
                      )}
                    </div>
                  ))}
                </div>
              ) : null}
              {mediaFailed ? (
                <Alert variant="warning" title="Media chưa tải được">
                  Hãy tải lại hồ sơ để cấp liên kết mới.
                </Alert>
              ) : null}
              <Button
                variant="outline"
                disabled={queue.isFetching}
                onClick={async () => {
                  await queue.refetch();
                  setMediaFailed(false);
                }}
              >
                Tải lại hồ sơ / media
              </Button>
              <section className="space-y-4">
                <h2 className="text-headline-5 font-serif font-normal">
                  Báo cáo ({selected.review.active_report_count})
                </h2>
                {selected.review.active_report_count >
                selected.reports.length ? (
                  <p className="text-caption text-muted-foreground">
                    Hiển thị {selected.reports.length} báo cáo đầu tiên. Gỡ báo
                    cáo áp dụng cho tất cả báo cáo đang hoạt động.
                  </p>
                ) : null}
                {selected.reports.map((report) => (
                  <div
                    key={report.report_id}
                    className="space-y-2 rounded-lg border border-border p-4"
                  >
                    <p>{REPORT_REASONS[report.reason] ?? "Nội dung vi phạm"}</p>
                    <p className="text-caption">
                      {formatDateTime(report.created_at)}
                    </p>
                    {report.detail ? (
                      <p className="whitespace-pre-wrap break-words text-[14px]">
                        {report.detail}
                      </p>
                    ) : null}
                  </div>
                ))}
              </section>
              <div className="flex flex-wrap gap-3">
                <Button onClick={() => openAction("dismiss")}>
                  Gỡ toàn bộ báo cáo
                </Button>
                <Button
                  variant="outline"
                  disabled={selected.review.visibility_status !== "APPROVED"}
                  onClick={() => openAction("reply")}
                >
                  Phản hồi chính thức
                </Button>
                <Button
                  variant="destructive"
                  disabled={unresolvedTakedown === selectedId}
                  onClick={() => openAction("takedown")}
                >
                  Gỡ theo yêu cầu pháp lý
                </Button>
              </div>
            </div>
          ) : null}
        </SheetContent>
      </Sheet>
      <Dialog
        open={action !== null}
        onOpenChange={(open) => {
          if (!open && !mutation.isPending) setAction(null);
        }}
        disablePointerDismissal={mutation.isPending}
      >
        <DialogContent showCloseButton={!mutation.isPending}>
          <form
            onSubmit={(event) => {
              event.preventDefault();
              if (
                !selectedId ||
                !action ||
                uncertainTakedown ||
                mutation.isPending ||
                submitError.isCoolingDown
              )
                return;
              mutation.mutate({
                action,
                id: selectedId,
                content: content.trim(),
                legalCase: legalCase.trim(),
              });
            }}
          >
            <DialogHeader>
              <DialogTitle>
                {action === "reply"
                  ? "Phản hồi chính thức"
                  : action === "dismiss"
                    ? "Gỡ toàn bộ báo cáo"
                    : "Gỡ đánh giá theo yêu cầu pháp lý"}
              </DialogTitle>
              <DialogDescription>
                {action === "dismiss"
                  ? "Gỡ tất cả báo cáo đang hoạt động; đánh giá bị ẩn sẽ được khôi phục công khai."
                  : action === "reply"
                    ? "Nội dung thay thế phản hồi chính thức hiện có. Chỉ đánh giá đang hiển thị được phản hồi."
                    : "Đánh giá sẽ bị gỡ vĩnh viễn. Cần mã hồ sơ pháp lý, lý do nội bộ và xác nhận mã đánh giá."}
              </DialogDescription>
            </DialogHeader>
            <div className="mt-6 space-y-5">
              {mutation.isError ? <ErrorState error={mutation.error} /> : null}
              {uncertainTakedown ? (
                <Alert variant="warning" title="Chưa xác định kết quả">
                  Yêu cầu có thể đã được ghi nhận. Tải lại hàng đợi và kiểm tra
                  hồ sơ trước khi thực hiện tiếp.
                  <Button
                    className="mt-3"
                    type="button"
                    variant="outline"
                    onClick={async () => {
                      const result = await queue.refetch();
                      if (!result.isError) {
                        setAction(null);
                        setSelectedId(null);
                        setUnresolvedTakedown(null);
                      }
                    }}
                  >
                    Tải lại hàng đợi
                  </Button>
                </Alert>
              ) : null}
              {action === "takedown" ? (
                <>
                  <Label htmlFor="legal-case">Mã hồ sơ pháp lý *</Label>
                  <Input
                    id="legal-case"
                    required
                    maxLength={128}
                    disabled={mutation.isPending || uncertainTakedown}
                    value={legalCase}
                    onChange={(event) => setLegalCase(event.target.value)}
                  />
                </>
              ) : null}
              {action !== "dismiss" ? (
                <div className="space-y-2">
                  <Label htmlFor="moderation-content">
                    {action === "reply"
                      ? "Nội dung phản hồi *"
                      : "Lý do nội bộ *"}
                  </Label>
                  <Textarea
                    id="moderation-content"
                    required
                    maxLength={5000}
                    disabled={mutation.isPending || uncertainTakedown}
                    value={content}
                    onChange={(event) => setContent(event.target.value)}
                  />
                </div>
              ) : null}
              {action === "takedown" ? (
                <div className="space-y-2">
                  <Label htmlFor="confirm-review">
                    Gõ {selectedId} để xác nhận *
                  </Label>
                  <Input
                    id="confirm-review"
                    autoComplete="off"
                    value={confirmation}
                    disabled={mutation.isPending || uncertainTakedown}
                    onChange={(event) => setConfirmation(event.target.value)}
                  />
                </div>
              ) : null}
            </div>
            <DialogFooter>
              <Button
                type="button"
                variant="secondary"
                disabled={mutation.isPending}
                onClick={() => setAction(null)}
              >
                Quay lại
              </Button>
              <Button
                type="submit"
                variant={action === "takedown" ? "destructive" : "default"}
                disabled={
                  mutation.isPending ||
                  uncertainTakedown ||
                  submitError.isCoolingDown ||
                  (action !== "dismiss" && !content.trim()) ||
                  (action === "takedown" &&
                    (!legalCase.trim() || confirmation !== selectedId))
                }
              >
                {mutation.isPending
                  ? "Đang ghi nhận"
                  : submitError.isCoolingDown
                    ? `Thử lại sau ${submitError.cooldown} giây`
                    : "Xác nhận"}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </div>
  );
}
