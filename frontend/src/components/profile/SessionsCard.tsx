"use client";

import { useState } from "react";
import { LoaderCircle, LogOut, MonitorSmartphone } from "lucide-react";

import { Alert } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { ErrorState } from "@/components/ui/error-state";
import { Skeleton } from "@/components/ui/skeleton";
import { useAuth } from "@/hooks/use-auth";
import { useRevokeAllSessions, useRevokeSession, useSessions } from "@/hooks/use-sessions";
import type { Session } from "@/lib/api/auth";
import { problemMessage, problemTraceId } from "@/lib/api/problem";
import { formatDateTime } from "@/lib/format";

/**
 * Các thiết bị đang đăng nhập. `id` của session là `family_id`, trùng với claim
 * `sid` của access token hiện tại — nhờ vậy nhận ra được "thiết bị này".
 */
export function SessionsCard() {
  const { sessionId } = useAuth();
  const sessions = useSessions();
  const revoke = useRevokeSession();
  const revokeAll = useRevokeAllSessions();
  const [confirmingAll, setConfirmingAll] = useState(false);

  const items = sessions.data?.pages.flatMap((page) => page.sessions) ?? [];
  // Thiết bị này luôn đứng đầu để người dùng không nhầm khi thu hồi.
  const ordered = [
    ...items.filter((session) => session.id === sessionId),
    ...items.filter((session) => session.id !== sessionId),
  ];

  const actionError = revoke.error ?? revokeAll.error;

  return (
    <Card>
      <CardHeader>
        <CardTitle>Thiết bị đang đăng nhập</CardTitle>
        <CardDescription>
          Thấy một thiết bị lạ? Đăng xuất nó rồi đổi mật khẩu ngay.
        </CardDescription>
      </CardHeader>

      <CardContent className="space-y-4">
        {actionError ? (
          <Alert variant="danger" traceId={problemTraceId(actionError)}>
            {problemMessage(actionError)}
          </Alert>
        ) : null}

        {sessions.isPending ? (
          <SessionListSkeleton />
        ) : sessions.isError ? (
          <ErrorState error={sessions.error} onRetry={() => void sessions.refetch()} />
        ) : (
          <ul className="divide-y divide-border" aria-label="Danh sách thiết bị">
            {ordered.map((session) => (
              <SessionRow
                key={session.id}
                session={session}
                isCurrent={session.id === sessionId}
                isRevoking={revoke.isPending && revoke.variables === session.id}
                disabled={revoke.isPending || revokeAll.isPending}
                onRevoke={() => revoke.mutate(session.id)}
              />
            ))}
          </ul>
        )}

        {sessions.hasNextPage && (
          <Button
            variant="outline"
            size="sm"
            onClick={() => void sessions.fetchNextPage()}
            disabled={sessions.isFetchingNextPage}
          >
            {sessions.isFetchingNextPage && (
              <LoaderCircle className="animate-spin" aria-hidden />
            )}
            Xem thêm thiết bị
          </Button>
        )}
      </CardContent>

      <CardFooter className="flex-wrap gap-3">
        {confirmingAll ? (
          <>
            <p className="w-full text-[14px] text-muted-foreground">
              Mọi thiết bị, kể cả thiết bị này, sẽ phải đăng nhập lại.
            </p>
            <Button
              variant="destructive"
              onClick={() => revokeAll.mutate()}
              disabled={revokeAll.isPending}
            >
              {revokeAll.isPending && <LoaderCircle className="animate-spin" aria-hidden />}
              Xác nhận đăng xuất tất cả
            </Button>
            <Button
              variant="ghost"
              onClick={() => setConfirmingAll(false)}
              disabled={revokeAll.isPending}
            >
              Huỷ
            </Button>
          </>
        ) : (
          <Button variant="outline" onClick={() => setConfirmingAll(true)}>
            <LogOut aria-hidden />
            Đăng xuất khỏi tất cả thiết bị
          </Button>
        )}
      </CardFooter>
    </Card>
  );
}

function SessionRow({
  session,
  isCurrent,
  isRevoking,
  disabled,
  onRevoke,
}: {
  session: Session;
  isCurrent: boolean;
  isRevoking: boolean;
  disabled: boolean;
  onRevoke: () => void;
}) {
  return (
    <li className="flex items-start gap-4 py-4 first:pt-0 last:pb-0">
      <MonitorSmartphone className="mt-0.5 size-5 shrink-0 text-muted-foreground" aria-hidden />

      <div className="min-w-0 flex-1 space-y-1">
        <div className="flex flex-wrap items-center gap-2">
          <p className="text-[15px] font-medium text-foreground">
            {session.device_name || "Thiết bị không rõ"}
          </p>
          {isCurrent && <Badge variant="clay">Thiết bị này</Badge>}
        </div>
        <p className="text-caption text-muted-foreground">
          Hoạt động gần nhất {formatDateTime(session.last_used_at)}
          {session.ip_address ? ` · IP ${session.ip_address}` : ""}
        </p>
        <p className="text-caption text-muted-foreground">
          Đăng nhập lúc {formatDateTime(session.created_at)}
        </p>
      </div>

      <Button
        variant="ghost"
        size="sm"
        onClick={onRevoke}
        disabled={disabled}
        aria-label={isCurrent ? "Đăng xuất thiết bị này" : `Đăng xuất ${session.device_name || "thiết bị"}`}
      >
        {isRevoking && <LoaderCircle className="animate-spin" aria-hidden />}
        Đăng xuất
      </Button>
    </li>
  );
}

function SessionListSkeleton() {
  return (
    <div className="divide-y divide-border" aria-busy="true">
      {[0, 1].map((key) => (
        <div key={key} className="flex items-start gap-4 py-4 first:pt-0">
          <Skeleton className="size-5 rounded-md" />
          <div className="flex-1 space-y-2">
            <Skeleton className="h-5 w-48" />
            <Skeleton className="h-4 w-64 max-w-full" />
          </div>
          <Skeleton className="h-9 w-24" />
        </div>
      ))}
    </div>
  );
}
