"use client";

import { useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { ConfirmDialog } from "@/components/admin/confirm-dialog";
import { PageHeader } from "@/components/admin/page-header";
import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { ErrorState } from "@/components/ui/error-state";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { adminSystemApi } from "@/lib/api/admin-system";
import { isCommandOutcomeUnknown } from "@/lib/admin-command";
import { useSubmitError } from "@/hooks/use-submit-error";
import { useAuthStore } from "@/store/useAuthStore";
import { formatDateTime } from "@/lib/format";

export function AccountDeletion() {
  const [accountId, setAccountId] = useState("");
  const [target, setTarget] = useState<string | null>(null);
  const submitError = useSubmitError();
  const mutation = useMutation({
    mutationFn: adminSystemApi.deleteAccount,
    retry: false,
    onError: (error) => {
      submitError.capture(error);
    },
  });
  const uncertain = mutation.isError && isCommandOutcomeUnknown(mutation.error);
  const valid =
    /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(
      accountId.trim(),
    );
  return (
    <div className="space-y-8">
      <PageHeader
        title="Xoá tài khoản"
        description="Yêu cầu xoá theo mã account. Backend chưa cung cấp danh sách hoặc API đọc trạng thái xoá cho quản trị viên."
      />
      <Alert variant="warning" title="Không thể hoàn tác">
        Tài khoản bị xoá mềm và mọi phiên bị thu hồi ngay khi yêu cầu được nhận.
        Hãy đối chiếu đúng mã tài khoản trước khi gửi.
      </Alert>
      <Card className="max-w-2xl">
        <CardContent className="space-y-6">
          <div className="space-y-2">
            <Label htmlFor="delete-account-id">Mã tài khoản (UUID) *</Label>
            <Input
              id="delete-account-id"
              maxLength={36}
              autoComplete="off"
              value={accountId}
              disabled={mutation.isPending || uncertain || mutation.isSuccess}
              onChange={(event) => {
                setAccountId(event.target.value);
                mutation.reset();
              }}
            />
          </div>
          {mutation.isError ? <ErrorState error={mutation.error} /> : null}
          {uncertain ? (
            <Alert variant="warning" title="Chưa xác định kết quả xoá">
              Yêu cầu có thể đã được nhận. API hiện không có cách đọc phục hồi
              kết quả xoá. Cần xác minh tài khoản này với người vận hành trước
              khi gửi yêu cầu tiếp.
            </Alert>
          ) : null}
          {mutation.data ? (
            <Alert variant="success" title="Đã nhận yêu cầu xoá">
              Tài khoản đã chuyển sang trạng thái xoá. Dự kiến xoá cứng sau{" "}
              {formatDateTime(mutation.data.hard_delete_after)}.
            </Alert>
          ) : null}
          <Button
            variant="destructive"
            disabled={
              !valid ||
              mutation.isPending ||
              uncertain ||
              mutation.isSuccess ||
              submitError.isCoolingDown
            }
            onClick={() => setTarget(accountId.trim())}
          >
            Yêu cầu xoá tài khoản
          </Button>
          {mutation.isSuccess ? (
            <Button
              variant="outline"
              onClick={() => {
                mutation.reset();
                setAccountId("");
              }}
            >
              Xử lý tài khoản khác
            </Button>
          ) : null}
        </CardContent>
      </Card>
      <ConfirmDialog
        key={target ?? "closed"}
        open={target !== null}
        onOpenChange={(open) => {
          if (!open) setTarget(null);
        }}
        title="Xác nhận xoá tài khoản"
        description={`Xoá tài khoản ${target ?? ""}. Thao tác không thể hoàn tác và thu hồi mọi phiên của tài khoản đó.`}
        expectedText={target ?? undefined}
        disabled={uncertain || submitError.isCoolingDown}
        showReason={false}
        reasonRequired={false}
        confirmLabel="Xoá tài khoản"
        onConfirm={async () => {
          if (!target || uncertain || mutation.isPending)
            throw new Error("invalid target");
          try {
            await mutation.mutateAsync(target);
            if (target === useAuthStore.getState().claims?.sub)
              useAuthStore.getState().clear("account_deleted");
          } finally {
            setTarget(null);
          }
        }}
      />
    </div>
  );
}
