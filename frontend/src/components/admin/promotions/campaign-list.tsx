"use client";

import { useCallback, useMemo } from "react";
import Link from "next/link";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import type { ColumnDef } from "@tanstack/react-table";
import { ChevronRight, Plus } from "lucide-react";

import { DataTable } from "@/components/admin/data-table";
import { FilterBar } from "@/components/admin/filter-bar";
import { PageHeader } from "@/components/admin/page-header";
import { StatusBadge } from "@/components/admin/status-badge";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { NativeSelect } from "@/components/ui/native-select";
import { useAdminCampaigns } from "@/hooks/use-admin-promotions";
import type {
  Campaign,
  CampaignListParams,
  CampaignPhase,
  CampaignPublicationStatus,
} from "@/lib/api/promotion";
import { formatDateTime } from "@/lib/format";
import { CAMPAIGN_PHASE_LABELS, CAMPAIGN_PUBLICATION_LABELS } from "@/lib/labels/promotion";

// UNKNOWN chỉ dành cho reader tương thích về sau; không phải giá trị để lọc.
const PUBLICATION_STATUSES = (
  Object.keys(CAMPAIGN_PUBLICATION_LABELS) as CampaignPublicationStatus[]
).filter((value) => value !== "UNKNOWN");
const PHASES = (Object.keys(CAMPAIGN_PHASE_LABELS) as CampaignPhase[]).filter(
  (value) => value !== "UNKNOWN"
);

function enumValue<T extends string>(values: readonly T[], value: string | null): T | undefined {
  return values.find((candidate) => candidate === value);
}

function filtersFrom(searchParams: URLSearchParams): CampaignListParams {
  const status = enumValue(PUBLICATION_STATUSES, searchParams.get("publication_status"));
  const phase = enumValue(PHASES, searchParams.get("phase"));
  return {
    ...(status ? { publication_status: status } : {}),
    ...(phase ? { phase } : {}),
  };
}

const detailHref = (campaignId: string) => `/admin/promotions/${encodeURIComponent(campaignId)}`;

function Schedule({ campaign }: { campaign: Campaign }) {
  return (
    <span className="whitespace-nowrap tabular-nums">
      {formatDateTime(campaign.start_time)} – {formatDateTime(campaign.end_time)}
    </span>
  );
}

export function CampaignList() {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const filters = useMemo(
    () => filtersFrom(new URLSearchParams(searchParams.toString())),
    [searchParams]
  );
  const campaignsQuery = useAdminCampaigns(filters);
  const campaigns = campaignsQuery.data?.items ?? [];

  const columns = useMemo<ColumnDef<Campaign, unknown>[]>(
    () => [
      {
        accessorKey: "name",
        header: "Campaign",
        cell: ({ row }) => (
          <div className="space-y-1">
            <Link
              href={detailHref(row.original.id)}
              className="focus-ring rounded-sm font-medium hover:underline"
            >
              {row.original.name}
            </Link>
            <p className="font-mono text-caption text-muted-foreground">{row.original.code}</p>
          </div>
        ),
      },
      {
        id: "status",
        header: "Trạng thái",
        cell: ({ row }) => (
          <div className="flex flex-wrap gap-2">
            <StatusBadge value={row.original.publication_status} labels={CAMPAIGN_PUBLICATION_LABELS} />
            {row.original.publication_status === "PUBLISHED" ? (
              <StatusBadge value={row.original.phase} labels={CAMPAIGN_PHASE_LABELS} />
            ) : null}
          </div>
        ),
      },
      {
        id: "schedule",
        header: "Lịch chạy",
        cell: ({ row }) => <Schedule campaign={row.original} />,
      },
      {
        accessorKey: "updated_at",
        header: "Cập nhật",
        cell: ({ row }) => (
          <time dateTime={row.original.updated_at} className="whitespace-nowrap tabular-nums">
            {formatDateTime(row.original.updated_at)}
          </time>
        ),
      },
      {
        id: "actions",
        header: "",
        cell: ({ row }) => (
          <Button
            variant="ghost"
            size="icon-sm"
            nativeButton={false}
            aria-label={`Mở campaign ${row.original.name}`}
            render={<Link href={detailHref(row.original.id)} />}
          >
            <ChevronRight aria-hidden />
          </Button>
        ),
      },
    ],
    []
  );

  const setFilter = useCallback(
    (name: string, value: string) => {
      const params = new URLSearchParams(searchParams.toString());
      if (value) params.set(name, value);
      else params.delete(name);
      const query = params.toString();
      router.replace(query ? `${pathname}?${query}` : pathname, { scroll: false });
    },
    [pathname, router, searchParams]
  );
  const resetFilters = useCallback(
    () => router.replace(pathname, { scroll: false }),
    [pathname, router]
  );

  return (
    <div className="space-y-8">
      <PageHeader
        title="Khuyến mãi"
        description="Campaign gom lịch chạy, voucher toàn đơn và giảm giá theo sản phẩm. Nội dung đã xuất bản không sửa được; muốn đổi hãy nhân bản thành bản nháp mới."
        action={
          <Button nativeButton={false} render={<Link href="/admin/promotions/new" />}>
            <Plus aria-hidden />
            Tạo campaign
          </Button>
        }
      />

      <FilterBar hasFilters={searchParams.size > 0} onReset={resetFilters}>
        <div className="space-y-2">
          <Label htmlFor="campaign-filter-status">Trạng thái xuất bản</Label>
          <NativeSelect
            id="campaign-filter-status"
            value={filters.publication_status ?? ""}
            onChange={(event) => setFilter("publication_status", event.target.value)}
          >
            <option value="">Tất cả trạng thái</option>
            {PUBLICATION_STATUSES.map((value) => (
              <option key={value} value={value}>{CAMPAIGN_PUBLICATION_LABELS[value].text}</option>
            ))}
          </NativeSelect>
        </div>
        <div className="space-y-2">
          <Label htmlFor="campaign-filter-phase">Giai đoạn</Label>
          <NativeSelect
            id="campaign-filter-phase"
            value={filters.phase ?? ""}
            onChange={(event) => setFilter("phase", event.target.value)}
          >
            <option value="">Tất cả giai đoạn</option>
            {PHASES.map((value) => (
              <option key={value} value={value}>{CAMPAIGN_PHASE_LABELS[value].text}</option>
            ))}
          </NativeSelect>
        </div>
      </FilterBar>

      <DataTable
        columns={columns}
        data={campaigns}
        getRowId={(campaign) => campaign.id}
        caption="Danh sách campaign khuyến mãi"
        isLoading={campaignsQuery.isPending}
        isFetching={campaignsQuery.isFetching && !campaignsQuery.isPending}
        error={campaignsQuery.error}
        onRetry={() => void campaignsQuery.refetch()}
        emptyTitle="Chưa có campaign nào"
        emptyDescription={
          searchParams.size > 0
            ? "Không có campaign khớp bộ lọc. Hãy thử bộ lọc khác."
            : "Tạo campaign đầu tiên để thêm voucher hoặc giảm giá theo sản phẩm."
        }
        emptyAction={
          searchParams.size > 0 ? (
            <Button variant="outline" size="sm" onClick={resetFilters}>Xoá bộ lọc</Button>
          ) : (
            <Button variant="outline" size="sm" nativeButton={false} render={<Link href="/admin/promotions/new" />}>
              Tạo campaign
            </Button>
          )
        }
        page={campaignsQuery.page}
        hasPrevious={campaignsQuery.hasPrevious}
        hasNext={campaignsQuery.hasNext}
        onPrevious={campaignsQuery.goPrevious}
        onNext={campaignsQuery.goNext}
        renderMobileCard={(campaign) => (
          <Link href={detailHref(campaign.id)} className="focus-ring block space-y-4 rounded-lg">
            <div className="flex items-start justify-between gap-4">
              <div className="min-w-0 space-y-1">
                <h2 className="truncate text-[15px] font-medium">{campaign.name}</h2>
                <p className="font-mono text-caption text-muted-foreground">{campaign.code}</p>
              </div>
              <StatusBadge value={campaign.publication_status} labels={CAMPAIGN_PUBLICATION_LABELS} />
            </div>
            <dl className="space-y-2 text-[14px]">
              <div>
                <dt className="text-caption text-muted-foreground">Lịch chạy</dt>
                <dd className="tabular-nums">{formatDateTime(campaign.start_time)} – {formatDateTime(campaign.end_time)}</dd>
              </div>
              {campaign.publication_status === "PUBLISHED" ? (
                <div>
                  <dt className="text-caption text-muted-foreground">Giai đoạn</dt>
                  <dd><StatusBadge value={campaign.phase} labels={CAMPAIGN_PHASE_LABELS} /></dd>
                </div>
              ) : null}
            </dl>
          </Link>
        )}
      />
    </div>
  );
}
