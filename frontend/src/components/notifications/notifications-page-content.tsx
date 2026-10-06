"use client";

import { usePathname, useRouter } from "next/navigation";
import { BellOff, CheckCheck, EyeOff, LoaderCircle, Mail, MailOpen } from "lucide-react";

import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/empty-state";
import { ErrorState } from "@/components/ui/error-state";
import { Skeleton } from "@/components/ui/skeleton";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { useToast } from "@/components/ui/toast";
import { useInbox, useInboxActions, useUnreadCount } from "@/hooks/use-notifications";
import { problemMessage } from "@/lib/api/problem";
import type { InboxItem } from "@/lib/api/notification";
import { formatDateTime } from "@/lib/format";
import { internalPathOf } from "@/lib/notification-link";
import { cn } from "@/lib/utils";

export function NotificationsPageContent({ unreadOnly }: { unreadOnly: boolean }) {
  const router = useRouter();
  const pathname = usePathname();
  const { addToast } = useToast();
  const inbox = useInbox(unreadOnly);
  const unread = useUnreadCount();
  const { setRead, archive, markAllRead } = useInboxActions();

  const items = inbox.data?.pages.flatMap((page) => page.items) ?? [];
  const hasUnreadLoaded = items.some((item) => !item.read);
  const busy = setRead.isPending || archive.isPending || markAllRead.isPending;

  function fail(error: unknown) {
    addToast({ type: "error", title: problemMessage(error) });
  }

  function open(item: InboxItem, path: string) {
    // Đánh dấu đã đọc không chặn việc chuyển trang; thất bại cũng không giữ người dùng lại.
    if (!item.read) setRead.mutate({ id: item.id, read: true });
    router.push(path);
  }

  return (
    <main className="mx-auto w-full max-w-small space-y-8 px-6 py-10 md:px-8 md:py-14">
      <header className="flex flex-col justify-between gap-4 sm:flex-row sm:items-end">
        <div className="space-y-2">
          <h1 className="font-serif text-headline-2 font-normal">Thông báo</h1>
          <p className="max-w-prose-narrow text-[15px] text-muted-foreground">
            Cập nhật về đơn hàng, đánh giá và tài khoản của bạn.
          </p>
        </div>
        <Button
          variant="outline"
          disabled={busy || (!hasUnreadLoaded && !(unread.data?.count))}
          onClick={() => markAllRead.mutate(undefined, { onError: fail })}
        >
          {markAllRead.isPending ? <LoaderCircle className="animate-spin" aria-hidden /> : <CheckCheck aria-hidden />}
          Đánh dấu tất cả đã đọc
        </Button>
      </header>

      <Tabs value={unreadOnly ? "unread" : "all"} onValueChange={(value) => router.replace(value === "unread" ? `${pathname}?filter=unread` : pathname, { scroll: false })}>
        <TabsList aria-label="Lọc thông báo">
          <TabsTrigger value="all">Tất cả</TabsTrigger>
          <TabsTrigger value="unread">Chưa đọc</TabsTrigger>
        </TabsList>
      </Tabs>

      {inbox.isPending ? (
        <div className="space-y-3" aria-busy="true" aria-label="Đang tải thông báo">
          {[0, 1, 2, 3].map((item) => <Skeleton key={item} className="h-24 w-full rounded-xl" />)}
        </div>
      ) : inbox.isError && !inbox.data ? (
        <ErrorState error={inbox.error} onRetry={() => void inbox.refetch()} />
      ) : (
        <section aria-label="Danh sách thông báo" className="space-y-4">
          {inbox.isError ? <ErrorState error={inbox.error} onRetry={() => void inbox.refetch()} /> : null}

          {items.length === 0 ? (
            <EmptyState
              icon={BellOff}
              title={unreadOnly ? "Bạn đã đọc hết thông báo" : "Chưa có thông báo nào"}
              description={unreadOnly ? "Thông báo mới sẽ hiện ở đây." : "Khi có cập nhật về đơn hàng hoặc đánh giá, bạn sẽ thấy chúng tại đây."}
            />
          ) : (
            <ul className="divide-y divide-border overflow-hidden rounded-xl border border-border bg-surface-overlay">
              {items.map((item) => {
                const path = internalPathOf(item.deep_link, window.location.origin);
                return (
                  <li key={item.id} className={cn("flex items-start gap-2 p-4 sm:p-5", !item.read && "bg-surface-2")}>
                    <div className="min-w-0 flex-1">
                      {path ? (
                        <button
                          type="button"
                          onClick={() => open(item, path)}
                          className="focus-ring w-full rounded-lg p-1 text-left"
                        >
                          <ItemText item={item} />
                        </button>
                      ) : (
                        <div className="p-1"><ItemText item={item} /></div>
                      )}
                    </div>
                    <div className="flex shrink-0 items-center">
                      <IconAction
                        label={item.read ? "Đánh dấu chưa đọc" : "Đánh dấu đã đọc"}
                        disabled={busy}
                        onClick={() => setRead.mutate({ id: item.id, read: !item.read }, { onError: fail })}
                      >
                        {item.read ? <Mail className="size-4" aria-hidden /> : <MailOpen className="size-4" aria-hidden />}
                      </IconAction>
                      <IconAction
                        label="Ẩn khỏi hộp thư"
                        disabled={busy}
                        onClick={() => archive.mutate(item.id, { onError: fail })}
                      >
                        <EyeOff className="size-4" aria-hidden />
                      </IconAction>
                    </div>
                  </li>
                );
              })}
            </ul>
          )}

          {inbox.hasNextPage ? (
            <div className="flex justify-center pt-2">
              <Button variant="outline" disabled={inbox.isFetchingNextPage} onClick={() => void inbox.fetchNextPage()}>
                {inbox.isFetchingNextPage ? <LoaderCircle className="animate-spin" aria-hidden /> : null}
                {inbox.isFetchingNextPage ? "Đang tải thêm…" : "Tải thêm thông báo"}
              </Button>
            </div>
          ) : null}
        </section>
      )}
    </main>
  );
}

function ItemText({ item }: { item: InboxItem }) {
  return (
    <span className="flex items-start gap-3">
      <span
        aria-hidden
        className={cn("mt-2 size-2 shrink-0 rounded-full", item.read ? "bg-transparent" : "bg-clay")}
      />
      <span className="min-w-0 space-y-1">
        <span className={cn("block text-[15px]", item.read ? "text-foreground-secondary" : "font-medium text-foreground")}>
          {!item.read ? <span className="sr-only">Chưa đọc: </span> : null}
          {item.title}
        </span>
        <span className="block text-[14px] text-muted-foreground">{item.body}</span>
        <time dateTime={item.created_at} className="block text-caption text-muted-foreground">
          {formatDateTime(item.created_at)}
        </time>
      </span>
    </span>
  );
}

function IconAction({
  label,
  disabled,
  onClick,
  children,
}: {
  label: string;
  disabled?: boolean;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      aria-label={label}
      title={label}
      disabled={disabled}
      onClick={onClick}
      className="focus-ring grid size-11 place-content-center rounded-lg text-muted-foreground transition-colors duration-200 hover:bg-accent hover:text-foreground disabled:cursor-not-allowed disabled:opacity-60"
    >
      {children}
    </button>
  );
}
