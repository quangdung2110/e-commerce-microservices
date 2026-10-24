"use client";

import { useQuery } from "@tanstack/react-query";

import { DetailSkeleton, ResultItem } from "@/components/admin/inventory/inventory-shared";
import { IdChip } from "@/components/admin/id-chip";
import { PageHeader } from "@/components/admin/page-header";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { ErrorState } from "@/components/ui/error-state";
import { inventoryAdminApi } from "@/lib/api/inventory-admin";
import { formatDateTime } from "@/lib/format";
import { inventoryErrorMessage } from "@/lib/inventory";
import { problemMessage } from "@/lib/api/problem";

export function CommandDetail({ commandId }: { commandId: string }) {
  const query = useQuery({
    queryKey: ["inventory", "command", commandId],
    queryFn: ({ signal }) => inventoryAdminApi.getCommandOutcome(commandId, signal),
  });

  if (query.isPending) return <DetailSkeleton />;
  if (query.isError) return <ErrorState error={query.error} message={inventoryErrorMessage(query.error)} onRetry={() => void query.refetch()} />;

  return (
    <div className="space-y-8">
      <PageHeader title="Kết quả lệnh" description="Đây là kết quả bền vững của đúng command ID, không phải trạng thái hiện tại của resource sau các lệnh tiếp theo." />
      <Card>
        <CardHeader className="border-b border-border">
          <CardTitle className="flex flex-wrap items-center gap-3">Command <Badge variant={query.data.result === "REJECTED" ? "destructive" : "success"}>{query.data.result === "REJECTED" ? "Đã từ chối" : "Đã chấp nhận"}</Badge></CardTitle>
        </CardHeader>
        <CardContent className="grid gap-6 pt-6 sm:grid-cols-2">
          <ResultItem label="Mã lệnh"><IdChip value={query.data.command_id} /></ResultItem>
          <ResultItem label="Correlation"><IdChip value={query.data.correlation_id} /></ResultItem>
          <ResultItem label="Thao tác"><span className="font-mono">{query.data.operation}</span></ResultItem>
          <ResultItem label="Kết quả"><span>{query.data.outcome}</span></ResultItem>
          {query.data.http_status !== undefined ? <ResultItem label="HTTP của lệnh gốc"><span>{query.data.http_status}</span></ResultItem> : null}
          {query.data.result === "REJECTED" ? (
            <>
              {query.data.error_code ? <ResultItem label="Mã lỗi"><span className="font-mono">{query.data.error_code}</span></ResultItem> : null}
              <ResultItem label="Nguyên nhân"><span>{problemMessage({ status: query.data.http_status ?? 409, problem: { code: query.data.error_code ?? "", status: query.data.http_status ?? 409 } }, "Lệnh đã bị từ chối. Hãy kiểm tra dữ liệu trước khi tạo yêu cầu mới.")}</span></ResultItem>
            </>
          ) : null}
          {query.data.resource_id ? <ResultItem label="Resource"><IdChip value={query.data.resource_id} /></ResultItem> : null}
          <ResultItem label="Ghi nhận lúc"><span>{formatDateTime(query.data.recorded_at)}</span></ResultItem>
        </CardContent>
      </Card>
    </div>
  );
}
