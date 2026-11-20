"use client";

import { useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ConfirmDialog } from "@/components/admin/confirm-dialog";
import { DataTable } from "@/components/admin/data-table";
import { PageHeader } from "@/components/admin/page-header";
import { StatusBadge } from "@/components/admin/status-badge";
import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { ErrorState } from "@/components/ui/error-state";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { NativeSelect } from "@/components/ui/native-select";
import { Skeleton } from "@/components/ui/skeleton";
import {
  adminSystemApi,
  type ReindexRun,
  type ReindexStatus,
} from "@/lib/api/admin-system";
import { problemMessage } from "@/lib/api/problem";
import { isCommandOutcomeUnknown } from "@/lib/admin-command";
import { useSubmitError } from "@/hooks/use-submit-error";
import { isActiveReindex, REINDEX_LABELS } from "@/lib/labels/system";
import { formatDateTime } from "@/lib/format";

export function SearchReindex() {
  const router = useRouter();
  const params = useSearchParams();
  const cache = useQueryClient();
  const rawStatus = params.get("status") ?? "";
  const status = Object.hasOwn(REINDEX_LABELS, rawStatus)
    ? (rawStatus as ReindexStatus)
    : undefined;
  const runId = params.get("run_id") ?? "";
  const [confirmation, setConfirmation] = useState<
    "create" | "cancel" | "rollback" | null
  >(null);
  const [mustRefresh, setMustRefresh] = useState(false);
  const submitError = useSubmitError();
  const runs = useQuery({
    queryKey: ["reindex-runs", status],
    queryFn: ({ signal }) => adminSystemApi.listRuns(status, signal),
    retry: false,
    refetchInterval: (query) =>
      !query.state.error &&
      query.state.data?.items.some((run) => isActiveReindex(run.status))
        ? 5000
        : false,
  });
  const run = useQuery({
    queryKey: ["reindex-run", runId],
    queryFn: ({ signal }) => adminSystemApi.getRun(runId, signal),
    enabled: !!runId,
    retry: false,
    refetchInterval: (query) =>
      !query.state.error &&
      query.state.data &&
      isActiveReindex(query.state.data.status)
        ? 3000
        : false,
  });
  function change(values: Record<string, string | undefined>) {
    const next = new URLSearchParams(params);
    for (const [key, value] of Object.entries(values)) {
      if (value) next.set(key, value);
      else next.delete(key);
    }
    router.replace(`/admin/system/search-reindex?${next}`);
  }
  const mutation = useMutation({
    retry: false,
    mutationFn: async (input: {
      action: "create" | "cancel" | "rollback";
      reason: string;
      runId: string;
    }) => {
      if (input.action === "create")
        return adminSystemApi.createRun(input.reason);
      return input.action === "cancel"
        ? adminSystemApi.cancelRun(input.runId)
        : adminSystemApi.rollbackRun(input.runId);
    },
    onSuccess: async (result) => {
      cache.setQueryData(["reindex-run", result.run_id], result);
      change({ run_id: result.run_id });
      await cache.invalidateQueries({ queryKey: ["reindex-runs"] });
    },
    onError: (error) => {
      submitError.capture(error);
      if (isCommandOutcomeUnknown(error)) setMustRefresh(true);
    },
  });
  const busy =
    runs.data?.items.some((item) => isActiveReindex(item.status)) ||
    (run.data && isActiveReindex(run.data.status));
  async function refresh() {
    const results = await Promise.all([
      runs.refetch(),
      ...(runId ? [run.refetch()] : []),
    ]);
    if (results.every((result) => !result.isError)) {
      setMustRefresh(false);
      mutation.reset();
    }
  }
  function open(value: "create" | "cancel" | "rollback") {
    mutation.reset();
    setConfirmation(value);
  }
  function summary(item: ReindexRun) {
    return (
      <div className="space-y-2">
        <StatusBadge value={item.status} labels={REINDEX_LABELS} />
        <p className="break-words">{item.reason}</p>
        <p className="text-caption text-muted-foreground">
          {formatDateTime(item.started_at)} · {item.actor}
        </p>
      </div>
    );
  }
  return (
    <div className="space-y-8">
      <PageHeader
        title="Tái lập chỉ mục Search"
        description="Chạy full reindex và theo dõi các lượt do quản trị viên hoặc hệ thống khởi tạo."
        action={
          <Button
            disabled={
              mutation.isPending ||
              mustRefresh ||
              submitError.isCoolingDown ||
              !!busy ||
              !runs.data ||
              runs.isError
            }
            onClick={() => open("create")}
          >
            Chạy full reindex
          </Button>
        }
      />
      {mutation.isError ? <ErrorState error={mutation.error} /> : null}
      {mustRefresh ? (
        <Alert variant="warning" title="Kết quả lệnh chưa rõ">
          Tải lại trạng thái để tìm lượt đã được nhận trước khi gửi lệnh tiếp.
        </Alert>
      ) : null}
      <div className="grid gap-5 rounded-xl border border-border bg-surface-2 p-6 sm:grid-cols-2">
        <div className="space-y-2">
          <Label htmlFor="run-status">Trạng thái</Label>
          <NativeSelect
            id="run-status"
            value={status ?? ""}
            onChange={(event) => change({ status: event.target.value })}
          >
            <option value="">Tất cả</option>
            {Object.entries(REINDEX_LABELS).map(([value, label]) => (
              <option key={value} value={value}>
                {label.text}
              </option>
            ))}
          </NativeSelect>
        </div>
        <form
          className="space-y-2"
          onSubmit={(event) => {
            event.preventDefault();
            const input = new FormData(event.currentTarget);
            change({ run_id: String(input.get("run_id") ?? "").trim() });
          }}
        >
          <Label htmlFor="run-id">Tra mã lượt chạy</Label>
          <div className="flex gap-2">
            <Input
              key={runId}
              id="run-id"
              name="run_id"
              required
              defaultValue={runId}
              maxLength={36}
            />
            <Button type="submit" variant="outline">
              Tra cứu
            </Button>
          </div>
        </form>
        <Button
          variant="outline"
          disabled={runs.isFetching || run.isFetching}
          onClick={() => void refresh()}
        >
          Tải lại trạng thái
        </Button>
      </div>
      <DataTable<ReindexRun>
        columns={[
          {
            id: "run",
            header: "Lượt chạy",
            cell: ({ row }) => summary(row.original),
          },
          {
            id: "id",
            header: "Mã lượt",
            cell: ({ row }) => (
              <span className="break-all font-mono text-caption">
                {row.original.run_id}
              </span>
            ),
          },
          {
            id: "action",
            header: "Thao tác",
            cell: ({ row }) => (
              <Button
                variant="outline"
                onClick={() => change({ run_id: row.original.run_id })}
              >
                Theo dõi
              </Button>
            ),
          },
        ]}
        data={runs.data?.items ?? []}
        getRowId={(item) => item.run_id}
        caption="50 lượt reindex gần nhất"
        renderMobileCard={(item) => (
          <div className="space-y-4">
            {summary(item)}
            <Button
              variant="outline"
              onClick={() => change({ run_id: item.run_id })}
            >
              Theo dõi
            </Button>
          </div>
        )}
        isLoading={runs.isPending}
        isFetching={runs.isFetching}
        error={!runs.data ? runs.error : undefined}
        partialError={runs.data ? runs.error : undefined}
        onRetry={() => void refresh()}
        emptyTitle="Chưa có lượt chạy phù hợp"
        emptyDescription="Chọn trạng thái khác hoặc bắt đầu full reindex mới."
        emptyAction={
          <Button
            variant="outline"
            onClick={() => change({ status: undefined })}
          >
            Xem tất cả
          </Button>
        }
      />
      {runId ? (
        run.isPending ? (
          <Skeleton className="h-64 rounded-xl" />
        ) : !run.data ? (
          <ErrorState error={run.error} onRetry={() => void run.refetch()} />
        ) : (
          <Card>
            <CardHeader>
              <CardTitle>Tiến độ lượt chạy</CardTitle>
            </CardHeader>
            <CardContent className="space-y-6">
              {summary(run.data)}
              {run.isError ? (
                <ErrorState
                  error={run.error}
                  message="Theo dõi bị gián đoạn. Lượt chạy có thể vẫn tiếp tục; hãy tải lại trạng thái."
                  onRetry={() => void run.refetch()}
                />
              ) : null}
              <dl className="grid grid-cols-2 gap-4 text-[14px]">
                <dt>Danh mục đã xuất</dt>
                <dd>{run.data.progress.categories_exported}</dd>
                <dt>Sản phẩm đã xuất</dt>
                <dd>{run.data.progress.products_exported}</dd>
                <dt>Tồn kho đã nhập</dt>
                <dd>{run.data.progress.stock_snapshots_imported}</dd>
                <dt>Điểm đánh giá đã nhập</dt>
                <dd>{run.data.progress.rating_aggregates_imported}</dd>
                <dt>Độ trễ đồng bộ</dt>
                <dd>{run.data.progress.catch_up_lag ?? "Chưa xác định"}</dd>
                <dt>Kết thúc lúc</dt>
                <dd>
                  {run.data.finished_at
                    ? formatDateTime(run.data.finished_at)
                    : "Đang chạy"}
                </dd>
              </dl>
              {run.data.failure ? (
                <Alert variant="warning" title="Thông tin kết thúc">
                  {problemMessage({
                    problem: { code: run.data.failure.code, status: 500 },
                  })}
                </Alert>
              ) : null}
              <div className="flex flex-wrap gap-3">
                <Button
                  variant="outline"
                  disabled={
                    mutation.isPending ||
                    mustRefresh ||
                    submitError.isCoolingDown ||
                    run.isError ||
                    !["STARTED", "BUILDING", "CATCHING_UP"].includes(
                      run.data.status,
                    )
                  }
                  onClick={() => open("cancel")}
                >
                  Huỷ lượt chạy
                </Button>
                <Button
                  variant="destructive"
                  disabled={
                    mutation.isPending ||
                    mustRefresh ||
                    submitError.isCoolingDown ||
                    run.isError ||
                    run.data.status !== "COMPLETED"
                  }
                  onClick={() => open("rollback")}
                >
                  Trả về chỉ mục cũ
                </Button>
              </div>
            </CardContent>
          </Card>
        )
      ) : null}
      <ConfirmDialog
        open={confirmation !== null}
        onOpenChange={(open) => {
          if (!open) setConfirmation(null);
        }}
        title={
          confirmation === "create"
            ? "Chạy full reindex"
            : confirmation === "cancel"
              ? "Huỷ lượt reindex"
              : "Trả về chỉ mục cũ"
        }
        description={
          confirmation === "create"
            ? "Search dựng chỉ mục mới, đồng bộ và kiểm tra trước khi chuyển sang sử dụng. Chỉ chạy được một lượt tại một thời điểm."
            : confirmation === "cancel"
              ? "Dừng ở trang tiếp theo. Chỉ mục đang phục vụ giữ nguyên; giai đoạn chuyển chỉ mục không thể huỷ."
              : "Chỉ lượt chuyển chỉ mục mới nhất được hoàn tác khi chỉ mục cũ còn tồn tại. Thay đổi sau lần chuyển chưa có trong chỉ mục cũ."
        }
        confirmLabel="Xác nhận"
        disabled={mustRefresh || submitError.isCoolingDown}
        showReason={confirmation === "create"}
        reasonMaxLength={500}
        reasonRequired={confirmation === "create"}
        destructive={confirmation !== "create"}
        expectedText={confirmation === "rollback" ? runId : undefined}
        onConfirm={async (reason) => {
          if (
            !confirmation ||
            mustRefresh ||
            (confirmation === "create" &&
              (reason.length > 500 || !reason.trim()))
          )
            throw new Error("invalid confirmation");
          await mutation.mutateAsync({ action: confirmation, reason, runId });
        }}
      />
    </div>
  );
}
