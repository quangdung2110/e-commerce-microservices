"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { LoaderCircle, MessageSquareText, PackageCheck } from "lucide-react";

import { MyReviewCard, PendingReviewCard, ReviewCardSkeleton } from "@/components/reviews/review-cards";
import { Button, buttonVariants } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/empty-state";
import { ErrorState } from "@/components/ui/error-state";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { useMyReviews, usePendingReviews } from "@/hooks/use-reviews";
import { cn } from "@/lib/utils";

export type ReviewsTab = "pending" | "written";

export function ReviewsPageContent({ tab }: { tab: ReviewsTab }) {
  const router = useRouter();
  const pathname = usePathname();

  return (
    <main className="mx-auto w-full max-w-small space-y-8 px-6 py-10 md:px-8 md:py-14">
      <header className="space-y-2">
        <h1 className="font-serif text-headline-2 font-normal">Đánh giá của tôi</h1>
        <p className="max-w-prose-narrow text-[15px] text-muted-foreground">
          Chia sẻ trải nghiệm sau khi nhận hàng và xem lại những đánh giá bạn đã viết.
        </p>
      </header>

      <Tabs
        value={tab}
        onValueChange={(value) => router.replace(value === "pending" ? pathname : `${pathname}?tab=${value}`, { scroll: false })}
      >
        <TabsList aria-label="Nhóm đánh giá">
          <TabsTrigger value="pending">Chờ đánh giá</TabsTrigger>
          <TabsTrigger value="written">Đã đánh giá</TabsTrigger>
        </TabsList>
      </Tabs>

      {tab === "pending" ? <PendingList /> : <WrittenList />}
    </main>
  );
}

function PendingList() {
  const pending = usePendingReviews();
  const items = pending.items;

  if (pending.isPending) return <ListSkeleton label="Đang tải sản phẩm chờ đánh giá" />;
  if (pending.isError && !pending.data) return <ErrorState error={pending.error} onRetry={() => void pending.refetch()} />;

  return (
    <section className="space-y-5" aria-label="Sản phẩm chờ đánh giá">
      {pending.isError ? <ErrorState error={pending.error} onRetry={() => void pending.refetch()} /> : null}
      {items.length === 0 ? (
        <EmptyState
          icon={PackageCheck}
          title="Bạn không có sản phẩm nào chờ đánh giá"
          description="Sau khi đơn hàng hoàn thành, từng sản phẩm đã nhận sẽ xuất hiện tại đây để bạn đánh giá trong thời hạn cho phép."
          action={<Link href="/orders" className={cn(buttonVariants({ variant: "outline", size: "sm" }))}>Xem đơn hàng</Link>}
        />
      ) : (
        <>
          <p className="text-caption text-muted-foreground">Sắp hết hạn được xếp trước.</p>
          {items.map((item) => <PendingReviewCard key={item.eligibility_id} item={item} />)}
          <LoadMore query={pending} label="Tải thêm sản phẩm" />
        </>
      )}
    </section>
  );
}

function WrittenList() {
  const written = useMyReviews();
  const items = written.data?.pages.flatMap((page) => page.items) ?? [];

  if (written.isPending) return <ListSkeleton label="Đang tải đánh giá của bạn" />;
  if (written.isError && !written.data) return <ErrorState error={written.error} onRetry={() => void written.refetch()} />;

  return (
    <section className="space-y-5" aria-label="Đánh giá đã viết">
      {written.isError ? <ErrorState error={written.error} onRetry={() => void written.refetch()} /> : null}
      {items.length === 0 ? (
        <EmptyState
          icon={MessageSquareText}
          title="Bạn chưa viết đánh giá nào"
          description="Đánh giá bạn gửi sẽ xuất hiện tại đây, kể cả khi sau đó bị ẩn để xem xét."
        />
      ) : (
        <>
          {items.map((review) => <MyReviewCard key={review.review_id} review={review} />)}
          <LoadMore query={written} label="Tải thêm đánh giá" />
        </>
      )}
    </section>
  );
}

function LoadMore({
  query,
  label,
}: {
  query: { hasNextPage: boolean; isFetchingNextPage: boolean; fetchNextPage: () => unknown };
  label: string;
}) {
  if (!query.hasNextPage) return null;
  return (
    <div className="flex justify-center pt-3">
      <Button variant="outline" disabled={query.isFetchingNextPage} onClick={() => void query.fetchNextPage()}>
        {query.isFetchingNextPage ? <LoaderCircle className="animate-spin" aria-hidden /> : null}
        {query.isFetchingNextPage ? "Đang tải thêm…" : label}
      </Button>
    </div>
  );
}

function ListSkeleton({ label }: { label: string }) {
  return (
    <div className="space-y-5" aria-busy="true" aria-label={label}>
      {[0, 1, 2].map((item) => <ReviewCardSkeleton key={item} />)}
    </div>
  );
}
