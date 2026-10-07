"use client";
import Link from "next/link";
import { useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { reviewApi, type ReportRequest } from "@/lib/api/review";
import { useAuthStore } from "@/store/useAuthStore";
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription, DialogFooter } from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { NativeSelect } from "@/components/ui/native-select";
import { Textarea } from "@/components/ui/textarea";
import { ErrorState } from "@/components/ui/error-state";
import { useToast } from "@/components/ui/toast";

export function ReportReview({ reviewId, slug }: { reviewId: string; slug: string }) {
  const [open, setOpen] = useState(false);
  const status = useAuthStore((state) => state.status);
  const { addToast } = useToast();
  const mutation = useMutation({ mutationFn: (body: ReportRequest) => reviewApi.report(reviewId, body), onSuccess: () => { setOpen(false); addToast({ type: "success", title: "Đã gửi báo cáo", message: "Cửa hàng sẽ xem xét đánh giá này." }); } });
  if (status === "anonymous") return <Link href={`/login?next=${encodeURIComponent(`/products/${slug}/reviews`)}`} className="focus-ring inline-flex min-h-11 items-center rounded-lg text-caption text-muted-foreground underline">Đăng nhập để báo cáo</Link>;
  return <>
    <Button variant="ghost" disabled={status === "loading"} onClick={() => { mutation.reset(); setOpen(true); }}>Báo cáo đánh giá</Button>
    <Dialog open={open} onOpenChange={(next) => { if (!mutation.isPending) setOpen(next); }}>
      <DialogContent><DialogHeader><DialogTitle>Báo cáo đánh giá</DialogTitle><DialogDescription>Chọn lý do để cửa hàng có thể xem xét.</DialogDescription></DialogHeader>
        <form className="space-y-5" onSubmit={(event) => { event.preventDefault(); const form = new FormData(event.currentTarget); mutation.mutate({ reason: String(form.get("reason")) as ReportRequest["reason"], detail: String(form.get("detail") ?? "").trim() || null }); }}>
          <div className="space-y-2"><label htmlFor={`report-reason-${reviewId}`}>Lý do</label><NativeSelect id={`report-reason-${reviewId}`} name="reason" disabled={mutation.isPending}><option value="SPAM">Nội dung rác</option><option value="OFFENSIVE">Nội dung xúc phạm</option><option value="FAKE">Đánh giá giả</option><option value="OTHER">Lý do khác</option></NativeSelect></div>
          <div className="space-y-2"><label htmlFor={`report-detail-${reviewId}`}>Thông tin thêm (không bắt buộc)</label><Textarea id={`report-detail-${reviewId}`} name="detail" maxLength={2000} disabled={mutation.isPending} /></div>
          {mutation.isError && <ErrorState error={mutation.error} />}
          <DialogFooter><Button type="button" variant="outline" disabled={mutation.isPending} onClick={() => setOpen(false)}>Hủy</Button><Button type="submit" disabled={mutation.isPending}>{mutation.isPending ? "Đang gửi…" : "Gửi báo cáo"}</Button></DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  </>;
}
