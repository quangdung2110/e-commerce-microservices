"use client";

import { useMemo, useState } from "react";
import Link from "next/link";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Ban,
  Copy,
  Pause,
  Percent,
  Plus,
  Rocket,
  Ticket,
  type LucideIcon,
} from "lucide-react";

import { ConfirmDialog } from "@/components/admin/confirm-dialog";
import { FormSection } from "@/components/admin/form-section";
import { IdChip } from "@/components/admin/id-chip";
import { PageHeader } from "@/components/admin/page-header";
import { CampaignFields } from "@/components/admin/promotions/campaign-form";
import { ProductName, StaleAlert, useNowMs } from "@/components/admin/promotions/promotion-common";
import { StatusBadge } from "@/components/admin/status-badge";
import { StickySaveBar } from "@/components/admin/sticky-save-bar";
import { Alert } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { EmptyState } from "@/components/ui/empty-state";
import { ErrorState } from "@/components/ui/error-state";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { useToast } from "@/components/ui/toast";
import {
  promotionKeys,
  refreshPromotionData,
  useAcceptCampaign,
  useCampaign,
  useCampaignMarkdowns,
  useCampaignVouchers,
} from "@/hooks/use-admin-promotions";
import { useSubmitError } from "@/hooks/use-submit-error";
import { isStaleVersion, type Versioned } from "@/lib/api/etag";
import {
  listAllPromotions,
  promotionApi,
  type Campaign,
  type MarkdownRule,
  type Promotion,
} from "@/lib/api/promotion";
import { formatDateTime, formatVND } from "@/lib/format";
import {
  CAMPAIGN_PHASE_LABELS,
  CAMPAIGN_PUBLICATION_LABELS,
  DISTRIBUTION_MODE_LABELS,
  type TransitionTarget,
} from "@/lib/labels/promotion";
import {
  campaignPatch,
  campaignToDraft,
  hasErrors,
  normalizeCode,
  validateCampaign,
  validateCode,
  type CampaignDraft,
} from "@/lib/promotion/campaign";
import { describeDiscount } from "@/lib/promotion/discount";

const TABS = ["info", "vouchers", "markdowns", "lifecycle"] as const;
type TabValue = (typeof TABS)[number];

const campaignHref = (campaignId: string) => `/admin/promotions/${encodeURIComponent(campaignId)}`;

export function CampaignDetail({ campaignId }: { campaignId: string }) {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const campaignQuery = useCampaign(campaignId);
  const acceptCampaign = useAcceptCampaign();
  const requested = searchParams.get("tab");
  const tab: TabValue = TABS.find((value) => value === requested) ?? "info";

  if (campaignQuery.isPending) return <CampaignDetailSkeleton />;
  if (campaignQuery.isError || !campaignQuery.data) {
    return <ErrorState error={campaignQuery.error} onRetry={() => void campaignQuery.refetch()} />;
  }

  const versioned = campaignQuery.data;
  const campaign = versioned.data;
  const reload = () => campaignQuery.refetch();

  function selectTab(next: TabValue) {
    const params = new URLSearchParams(searchParams.toString());
    if (next === "info") params.delete("tab");
    else params.set("tab", next);
    const query = params.toString();
    router.replace(query ? `${pathname}?${query}` : pathname, { scroll: false });
  }

  return (
    <div className="space-y-8">
      <PageHeader
        title={campaign.name}
        description={`Revision ${campaign.revision} · cập nhật ${formatDateTime(campaign.updated_at)} bởi ${campaign.updated_by}`}
      />

      <div className="flex flex-wrap items-center gap-3">
        <StatusBadge value={campaign.publication_status} labels={CAMPAIGN_PUBLICATION_LABELS} />
        {campaign.publication_status === "PUBLISHED" ? (
          <StatusBadge value={campaign.phase} labels={CAMPAIGN_PHASE_LABELS} />
        ) : null}
        <span className="font-mono text-caption text-muted-foreground">{campaign.code}</span>
        <IdChip value={campaign.id} label="Sao chép mã campaign" />
        {!versioned.etag ? (
          <Alert variant="warning">Response không có ETag nên các lệnh sửa đang bị khoá. Hãy tải lại dữ liệu.</Alert>
        ) : null}
      </div>

      <Tabs value={tab} onValueChange={(value) => selectTab(value as TabValue)}>
        <TabsList aria-label="Nội dung campaign">
          <TabsTrigger value="info">Thông tin và lịch</TabsTrigger>
          <TabsTrigger value="vouchers">Voucher</TabsTrigger>
          <TabsTrigger value="markdowns">Giảm giá sản phẩm</TabsTrigger>
          <TabsTrigger value="lifecycle">Vòng đời</TabsTrigger>
        </TabsList>
        <TabsContent value="info">
          <CampaignInfo key={versioned.etag ?? campaign.revision} versioned={versioned} onSaved={acceptCampaign} onReload={reload} />
        </TabsContent>
        <TabsContent value="vouchers">
          <VoucherList campaign={campaign} />
        </TabsContent>
        <TabsContent value="markdowns">
          <MarkdownList campaign={campaign} />
        </TabsContent>
        <TabsContent value="lifecycle">
          <CampaignLifecycle versioned={versioned} onSaved={acceptCampaign} onReload={reload} />
        </TabsContent>
      </Tabs>
    </div>
  );
}

/* ─────────────────────────── Thông tin và lịch ─────────────────────────── */

function CampaignInfo({
  versioned,
  onSaved,
  onReload,
}: {
  versioned: Versioned<Campaign>;
  onSaved: (next: Versioned<Campaign>) => void;
  onReload: () => void | Promise<unknown>;
}) {
  const campaign = versioned.data;
  const editable = campaign.publication_status === "DRAFT";
  const { addToast } = useToast();
  const submitError = useSubmitError();
  const [draft, setDraft] = useState<CampaignDraft>(() => campaignToDraft(campaign));
  const [showErrors, setShowErrors] = useState(false);
  const [stale, setStale] = useState(false);

  const errors = useMemo(() => validateCampaign(draft, { checkCode: false }), [draft]);
  const patch = useMemo(() => campaignPatch(campaign, draft), [campaign, draft]);

  const mutation = useMutation({
    mutationFn: () => {
      if (!versioned.etag) throw new Error("MISSING_ETAG");
      if (!patch) throw new Error("NO_CHANGES");
      return promotionApi.updateCampaign(campaign.id, versioned.etag, patch);
    },
    onSuccess: (next) => {
      onSaved(next);
      addToast({ type: "success", title: "Đã lưu campaign" });
    },
  });

  async function save() {
    submitError.reset();
    setShowErrors(true);
    if (hasErrors(errors) || !patch) return;
    try {
      await mutation.mutateAsync();
    } catch (error) {
      setStale(isStaleVersion(error));
      submitError.capture(error);
    }
  }

  return (
    <form onSubmit={(event) => { event.preventDefault(); void save(); }} className="space-y-6" noValidate>
      {submitError.message ? <ErrorState error={mutation.error} message={submitError.message} /> : null}
      {stale ? <StaleAlert onReload={async () => { setStale(false); await onReload(); }} /> : null}
      {editable ? null : (
        <Alert variant="info" title="Campaign đã xuất bản nên không sửa được">
          Nội dung và lịch chạy bất biến sau khi xuất bản. Muốn đổi, hãy nhân bản campaign thành bản nháp mới ở tab Vòng đời.
        </Alert>
      )}
      <FormSection title="Thông tin và lịch chạy">
        <CampaignFields
          idPrefix="campaign"
          draft={draft}
          errors={showErrors ? errors : {}}
          onChange={setDraft}
          codeEditable={false}
          disabled={!editable || mutation.isPending}
        />
        <dl className="grid gap-4 border-t border-border pt-5 text-[14px] sm:grid-cols-2">
          <div><dt className="text-caption text-muted-foreground">Tạo bởi</dt><dd>{campaign.created_by} · {formatDateTime(campaign.created_at)}</dd></div>
          <div><dt className="text-caption text-muted-foreground">Sửa lần cuối</dt><dd>{campaign.updated_by} · {formatDateTime(campaign.updated_at)}</dd></div>
        </dl>
      </FormSection>
      {editable ? (
        <StickySaveBar
          isDirty={patch !== null}
          isSaving={mutation.isPending}
          onSave={() => void save()}
          onDiscard={() => { setDraft(campaignToDraft(campaign)); setShowErrors(false); submitError.reset(); }}
        />
      ) : null}
    </form>
  );
}

/* ─────────────────────────── Danh sách voucher ─────────────────────────── */

function VoucherList({ campaign }: { campaign: Campaign }) {
  const query = useCampaignVouchers(campaign.id);
  const items = query.data?.pages.flatMap((page) => page.items) ?? [];
  const draft = campaign.publication_status === "DRAFT";
  const addHref = `${campaignHref(campaign.id)}/vouchers/new`;

  return (
    <div className="space-y-5">
      <ListToolbar
        description="Voucher áp dụng cho toàn đơn; mỗi đơn dùng tối đa một voucher."
        action={draft ? (
          <Button variant="outline" nativeButton={false} render={<Link href={addHref} />}><Plus aria-hidden />Thêm voucher</Button>
        ) : undefined}
      />
      {query.isPending ? <ListSkeleton /> : null}
      {query.isError ? <ErrorState error={query.error} onRetry={() => void query.refetch()} /> : null}
      {query.isSuccess && items.length === 0 ? (
        <EmptyState
          icon={Ticket}
          title="Chưa có voucher nào"
          description={draft ? "Thêm voucher để người mua nhận hoặc được cấp mã giảm giá toàn đơn." : "Campaign này đã xuất bản mà không có voucher."}
          action={draft ? <Button variant="outline" size="sm" nativeButton={false} render={<Link href={addHref} />}>Thêm voucher</Button> : undefined}
        />
      ) : null}
      {items.length > 0 ? (
        <ul className="grid gap-4 lg:grid-cols-2">
          {items.map((promotion) => (
            <li key={promotion.id}><VoucherCard campaignId={campaign.id} promotion={promotion} /></li>
          ))}
        </ul>
      ) : null}
      <LoadMore query={query} />
    </div>
  );
}

function VoucherCard({ campaignId, promotion }: { campaignId: string; promotion: Promotion }) {
  const budget = promotion.budget;
  return (
    <Link
      href={`${campaignHref(campaignId)}/vouchers/${encodeURIComponent(promotion.id)}`}
      className="focus-ring block space-y-4 rounded-xl border border-border bg-surface-overlay p-6 transition-colors duration-200 hover:border-border-strong hover:bg-surface-2"
    >
      <div className="flex items-start justify-between gap-4">
        <div className="min-w-0 space-y-1">
          <h3 className="truncate text-headline-6 font-serif font-normal">{promotion.name}</h3>
          <p className="font-mono text-caption text-muted-foreground">{promotion.code}</p>
        </div>
        <Badge variant={promotion.is_enabled ? "success" : "default"}>{promotion.is_enabled ? "Đang bật" : "Đã tắt"}</Badge>
      </div>
      <div className="flex flex-wrap gap-2">
        <StatusBadge value={promotion.distribution_mode} labels={DISTRIBUTION_MODE_LABELS} />
      </div>
      <dl className="grid grid-cols-2 gap-3 text-[14px]">
        <div><dt className="text-caption text-muted-foreground">Mức giảm</dt><dd className="font-medium">{describeDiscount(promotion.discount, formatVND)}</dd></div>
        <div><dt className="text-caption text-muted-foreground">Đơn tối thiểu</dt><dd className="tabular-nums">{formatVND(promotion.min_order_amount.amount_minor)}</dd></div>
        <div><dt className="text-caption text-muted-foreground">Đã nhận</dt><dd className="tabular-nums">{budget.claimed_quantity.toLocaleString("vi-VN")}{budget.total_claim_quantity === null ? "" : ` / ${budget.total_claim_quantity.toLocaleString("vi-VN")}`}</dd></div>
        <div><dt className="text-caption text-muted-foreground">Đã dùng</dt><dd className="tabular-nums">{budget.used_quantity.toLocaleString("vi-VN")}{budget.total_redemption_quantity === null ? "" : ` / ${budget.total_redemption_quantity.toLocaleString("vi-VN")}`}</dd></div>
      </dl>
    </Link>
  );
}

/* ─────────────────────────── Danh sách markdown ─────────────────────────── */

function MarkdownList({ campaign }: { campaign: Campaign }) {
  const query = useCampaignMarkdowns(campaign.id);
  const items = query.data?.pages.flatMap((page) => page.items) ?? [];
  const draft = campaign.publication_status === "DRAFT";
  const addHref = `${campaignHref(campaign.id)}/markdowns/new`;

  return (
    <div className="space-y-5">
      <ListToolbar
        description="Giảm trực tiếp giá bán của một sản phẩm khi campaign đang diễn ra; không cần voucher."
        action={draft ? (
          <Button variant="outline" nativeButton={false} render={<Link href={addHref} />}><Plus aria-hidden />Thêm giảm giá</Button>
        ) : undefined}
      />
      {query.isPending ? <ListSkeleton /> : null}
      {query.isError ? <ErrorState error={query.error} onRetry={() => void query.refetch()} /> : null}
      {query.isSuccess && items.length === 0 ? (
        <EmptyState
          icon={Percent}
          title="Chưa có giảm giá theo sản phẩm"
          description={draft ? "Thêm sản phẩm và mức giảm để giá bán tự động giảm khi campaign diễn ra." : "Campaign này đã xuất bản mà không có giảm giá sản phẩm."}
          action={draft ? <Button variant="outline" size="sm" nativeButton={false} render={<Link href={addHref} />}>Thêm giảm giá</Button> : undefined}
        />
      ) : null}
      {items.length > 0 ? (
        <ul className="grid gap-4 lg:grid-cols-2">
          {items.map((rule) => (
            <li key={rule.id}><MarkdownCard campaignId={campaign.id} rule={rule} /></li>
          ))}
        </ul>
      ) : null}
      <LoadMore query={query} />
    </div>
  );
}

function MarkdownCard({ campaignId, rule }: { campaignId: string; rule: MarkdownRule }) {
  return (
    <Link
      href={`${campaignHref(campaignId)}/markdowns/${encodeURIComponent(rule.id)}`}
      className="focus-ring block space-y-4 rounded-xl border border-border bg-surface-overlay p-6 transition-colors duration-200 hover:border-border-strong hover:bg-surface-2"
    >
      <div className="flex items-start justify-between gap-4">
        <h3 className="min-w-0 truncate text-headline-6 font-serif font-normal"><ProductName productId={rule.product_id} /></h3>
        {rule.invalidated_at ? (
          <Badge variant="warning">Vô hiệu hoá</Badge>
        ) : (
          <Badge variant={rule.is_enabled ? "success" : "default"}>{rule.is_enabled ? "Đang bật" : "Đã tắt"}</Badge>
        )}
      </div>
      <dl className="grid grid-cols-2 gap-3 text-[14px]">
        <div><dt className="text-caption text-muted-foreground">Mức giảm</dt><dd className="font-medium">{describeDiscount(rule.discount, formatVND)}</dd></div>
        <div><dt className="text-caption text-muted-foreground">Độ ưu tiên</dt><dd className="tabular-nums">{rule.priority}</dd></div>
      </dl>
    </Link>
  );
}

/* ─────────────────────────── Vòng đời ─────────────────────────── */

interface TransitionOption {
  target: TransitionTarget;
  label: string;
  icon: LucideIcon;
  title: string;
  description: string;
  destructive: boolean;
  retype: boolean;
}

const TRANSITIONS: Record<string, TransitionOption[]> = {
  DRAFT: [
    {
      target: "PUBLISHED",
      label: "Xuất bản",
      icon: Rocket,
      title: "Xuất bản campaign?",
      description:
        "Sau khi xuất bản, voucher và giảm giá sản phẩm không sửa được nữa. Voucher số lượng giới hạn sinh sẵn các suất nhận. Campaign chạy theo lịch đã đặt.",
      destructive: false,
      retype: false,
    },
  ],
  PUBLISHED: [
    {
      target: "PAUSED",
      label: "Tạm dừng",
      icon: Pause,
      title: "Tạm dừng campaign?",
      description:
        "Dừng nhận voucher mới và dừng giảm giá sản phẩm. Voucher người mua đã lưu vẫn dùng được đến hạn. Không có thao tác tiếp tục: muốn chạy lại phải nhân bản campaign.",
      destructive: true,
      retype: false,
    },
    {
      target: "CANCELLED",
      label: "Huỷ campaign",
      icon: Ban,
      title: "Huỷ campaign?",
      description:
        "Campaign kết thúc vĩnh viễn: dừng nhận voucher mới và giảm giá sản phẩm. Voucher đã lưu không bị thu hồi. Không thể hoàn tác.",
      destructive: true,
      retype: true,
    },
  ],
  PAUSED: [
    {
      target: "CANCELLED",
      label: "Huỷ campaign",
      icon: Ban,
      title: "Huỷ campaign?",
      description: "Campaign kết thúc vĩnh viễn. Voucher đã lưu không bị thu hồi. Không thể hoàn tác.",
      destructive: true,
      retype: true,
    },
  ],
};

function CampaignLifecycle({
  versioned,
  onSaved,
  onReload,
}: {
  versioned: Versioned<Campaign>;
  onSaved: (next: Versioned<Campaign>) => void;
  onReload: () => void | Promise<unknown>;
}) {
  const campaign = versioned.data;
  const { addToast } = useToast();
  const submitError = useSubmitError();
  // Giữ nội dung của lần mở gần nhất để hộp thoại không trống chữ trong lúc đóng.
  const [pending, setPending] = useState<TransitionOption | null>(null);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [cloneOpen, setCloneOpen] = useState(false);
  const [stale, setStale] = useState(false);
  const options = TRANSITIONS[campaign.publication_status] ?? [];
  const now = useNowMs();
  const endedAlready = new Date(campaign.end_time).getTime() <= now;

  const mutation = useMutation({
    mutationFn: ({ target, reason }: { target: TransitionTarget; reason: string }) => {
      if (!versioned.etag) throw new Error("MISSING_ETAG");
      return promotionApi.transitionCampaign(campaign.id, versioned.etag, { target_status: target, reason });
    },
    onSuccess: (next, variables) => {
      onSaved(next);
      addToast({
        type: "success",
        title:
          variables.target === "PUBLISHED" ? "Đã xuất bản campaign" : variables.target === "PAUSED" ? "Đã tạm dừng campaign" : "Đã huỷ campaign",
      });
    },
  });

  return (
    <div className="space-y-6">
      {stale ? <StaleAlert onReload={async () => { setStale(false); await onReload(); }} /> : null}
      {submitError.message ? <ErrorState error={mutation.error} message={submitError.message} /> : null}

      <FormSection
        title="Trạng thái xuất bản"
        description="Chuyển hợp lệ: nháp → xuất bản → tạm dừng hoặc huỷ; tạm dừng → huỷ. Không có thao tác tiếp tục."
      >
        <div className="flex flex-wrap items-center gap-3">
          <StatusBadge value={campaign.publication_status} labels={CAMPAIGN_PUBLICATION_LABELS} />
          {campaign.publication_status === "PUBLISHED" ? <StatusBadge value={campaign.phase} labels={CAMPAIGN_PHASE_LABELS} /> : null}
          <span className="text-[14px] text-muted-foreground tabular-nums">
            {formatDateTime(campaign.start_time)} – {formatDateTime(campaign.end_time)}
          </span>
        </div>
        {campaign.publication_status === "DRAFT" && endedAlready ? (
          <Alert variant="warning">Thời điểm kết thúc đã qua nên campaign chưa thể xuất bản. Hãy sửa lịch ở tab Thông tin và lịch.</Alert>
        ) : null}
        {options.length === 0 ? (
          <p className="text-[14px] text-muted-foreground">Campaign đã huỷ nên không còn chuyển trạng thái được. Có thể nhân bản để tạo bản nháp mới.</p>
        ) : (
          <div className="flex flex-wrap gap-3">
            {options.map((option) => (
              <Button
                key={option.target}
                type="button"
                variant={option.target === "PUBLISHED" ? "default" : "outline"}
                disabled={!versioned.etag || stale || (option.target === "PUBLISHED" && endedAlready)}
                onClick={() => { submitError.reset(); setPending(option); setConfirmOpen(true); }}
              >
                <option.icon aria-hidden />
                {option.label}
              </Button>
            ))}
          </div>
        )}
      </FormSection>

      <FormSection title="Nhân bản" description="Voucher và giảm giá sản phẩm đã xuất bản không sửa được. Nhân bản tạo campaign nháp mới với bản sao nội dung để chỉnh và xuất bản lại.">
        <div>
          <Button type="button" variant="outline" disabled={!versioned.etag || stale} onClick={() => setCloneOpen(true)}>
            <Copy aria-hidden />
            Nhân bản campaign
          </Button>
        </div>
      </FormSection>

      <ConfirmDialog
        open={confirmOpen}
        onOpenChange={setConfirmOpen}
        title={pending?.title ?? ""}
        description={pending?.description ?? ""}
        confirmLabel={pending?.label ?? "Xác nhận"}
        destructive={pending?.destructive ?? false}
        expectedText={pending?.retype ? campaign.code : undefined}
        onConfirm={async (reason) => {
          if (!pending) return;
          try {
            await mutation.mutateAsync({ target: pending.target, reason });
          } catch (error) {
            setStale(isStaleVersion(error));
            throw error;
          }
        }}
      />

      {cloneOpen ? <CloneDialog versioned={versioned} onOpenChange={setCloneOpen} onStale={() => setStale(true)} /> : null}
    </div>
  );
}

function CloneDialog({
  versioned,
  onOpenChange,
  onStale,
}: {
  versioned: Versioned<Campaign>;
  onOpenChange: (open: boolean) => void;
  onStale: () => void;
}) {
  const source = versioned.data;
  const router = useRouter();
  const queryClient = useQueryClient();
  const { addToast } = useToast();
  const submitError = useSubmitError();
  const [draft, setDraft] = useState<CampaignDraft>({
    code: "",
    name: `${source.name} (bản sao)`,
    description: source.description ?? "",
    startTime: undefined,
    endTime: undefined,
  });
  const [codes, setCodes] = useState<Record<string, string>>({});
  const [showErrors, setShowErrors] = useState(false);

  const promotionsQuery = useQuery({
    queryKey: [...promotionKeys.campaign(source.id), "all-vouchers"],
    queryFn: ({ signal }) => listAllPromotions(source.id, signal),
  });
  const promotions = promotionsQuery.data ?? [];

  const campaignErrors = validateCampaign(draft, { checkCode: true });
  const promotionErrors = Object.fromEntries(
    promotions.map((promotion) => [promotion.id, validateCode(codes[promotion.id] ?? "")])
  );
  const duplicateCodes = new Set(
    promotions
      .map((promotion) => codes[promotion.id])
      .filter((code, index, all) => Boolean(code) && all.indexOf(code) !== index)
  );

  const mutation = useMutation({
    mutationFn: () => {
      if (!versioned.etag) throw new Error("MISSING_ETAG");
      return promotionApi.cloneCampaign(source.id, versioned.etag, {
        code: draft.code,
        name: draft.name.trim(),
        description: draft.description.trim() || null,
        start_time: draft.startTime as string,
        end_time: draft.endTime as string,
        promotion_code_overrides: promotions.map((promotion) => ({
          source_promotion_id: promotion.id,
          new_code: codes[promotion.id],
        })),
      });
    },
    onSuccess: async (created) => {
      await refreshPromotionData(queryClient);
      addToast({ type: "success", title: "Đã nhân bản thành campaign nháp" });
      onOpenChange(false);
      router.push(campaignHref(created.data.id));
    },
  });

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    submitError.reset();
    setShowErrors(true);
    const invalid =
      hasErrors(campaignErrors) ||
      !promotionsQuery.isSuccess ||
      Object.values(promotionErrors).some(Boolean) ||
      duplicateCodes.size > 0;
    if (invalid) return;
    try {
      await mutation.mutateAsync();
    } catch (error) {
      if (isStaleVersion(error)) onStale();
      submitError.capture(error);
    }
  }

  return (
    <Dialog open onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[90svh] max-w-2xl overflow-y-auto">
        <DialogHeader>
          <DialogTitle>Nhân bản campaign</DialogTitle>
          <DialogDescription>
            Tạo campaign nháp mới kèm bản sao voucher và giảm giá sản phẩm. Mỗi voucher cần một mã mới; campaign gốc giữ nguyên.
          </DialogDescription>
        </DialogHeader>
        <form onSubmit={submit} className="mt-6 space-y-6" noValidate>
          {submitError.message ? <ErrorState error={mutation.error} message={submitError.message} /> : null}
          <div className="space-y-5">
            <CampaignFields
              idPrefix="clone"
              draft={draft}
              errors={showErrors ? campaignErrors : {}}
              onChange={setDraft}
              codeEditable
              disabled={mutation.isPending}
            />
          </div>

          <fieldset className="space-y-3" disabled={mutation.isPending}>
            <legend className="text-[14px] font-medium">Mã mới cho từng voucher</legend>
            {promotionsQuery.isPending ? <Skeleton className="h-11 w-full" /> : null}
            {promotionsQuery.isError ? (
              <ErrorState error={promotionsQuery.error} onRetry={() => void promotionsQuery.refetch()} />
            ) : null}
            {promotionsQuery.isSuccess && promotions.length === 0 ? (
              <p className="text-caption text-muted-foreground">Campaign này chưa có voucher nên không cần mã mới.</p>
            ) : null}
            {promotions.map((promotion) => {
              const message =
                (showErrors && promotionErrors[promotion.id]) ||
                (showErrors && duplicateCodes.has(codes[promotion.id]) ? "Mã bị trùng với voucher khác trong lần nhân bản này." : null);
              return (
                <div key={promotion.id} className="grid gap-2 sm:grid-cols-[minmax(0,1fr)_minmax(0,1fr)] sm:items-start">
                  <div>
                    <p className="truncate text-[14px] font-medium">{promotion.name}</p>
                    <p className="font-mono text-caption text-muted-foreground">Mã cũ: {promotion.code}</p>
                  </div>
                  <div className="space-y-1">
                    <Label htmlFor={`clone-code-${promotion.id}`} className="sr-only">Mã mới cho {promotion.name}</Label>
                    <Input
                      id={`clone-code-${promotion.id}`}
                      value={codes[promotion.id] ?? ""}
                      className="font-mono"
                      autoComplete="off"
                      placeholder="MÃ MỚI"
                      aria-invalid={message ? true : undefined}
                      onChange={(event) => setCodes((current) => ({ ...current, [promotion.id]: normalizeCode(event.target.value) }))}
                    />
                    {message ? <p role="alert" className="text-caption text-danger">{message}</p> : null}
                  </div>
                </div>
              );
            })}
          </fieldset>

          <DialogFooter>
            <Button type="button" variant="secondary" disabled={mutation.isPending} onClick={() => onOpenChange(false)}>Huỷ</Button>
            <Button type="submit" disabled={mutation.isPending || !versioned.etag || !promotionsQuery.isSuccess}>
              {mutation.isPending ? "Đang nhân bản" : "Nhân bản"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

/* ─────────────────────────── Dùng chung ─────────────────────────── */

function ListToolbar({ description, action }: { description: string; action?: React.ReactNode }) {
  return (
    <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
      <p className="max-w-prose-narrow text-[14px] text-muted-foreground">{description}</p>
      {action}
    </div>
  );
}

function LoadMore({
  query,
}: {
  query: { hasNextPage: boolean; isFetchingNextPage: boolean; fetchNextPage: () => unknown };
}) {
  if (!query.hasNextPage) return null;
  return (
    <div className="flex justify-center">
      <Button variant="outline" disabled={query.isFetchingNextPage} onClick={() => void query.fetchNextPage()}>
        {query.isFetchingNextPage ? "Đang tải" : "Tải thêm"}
      </Button>
    </div>
  );
}

function ListSkeleton() {
  return (
    <div className="grid gap-4 lg:grid-cols-2" aria-busy="true" aria-label="Đang tải danh sách">
      <Skeleton className="h-52 rounded-xl" />
      <Skeleton className="h-52 rounded-xl" />
    </div>
  );
}

function CampaignDetailSkeleton() {
  return (
    <div className="space-y-8" aria-busy="true" aria-label="Đang tải campaign">
      <div className="space-y-3"><Skeleton className="h-10 w-72" /><Skeleton className="h-4 w-80 max-w-full" /></div>
      <Skeleton className="h-11 w-full" />
      <Skeleton className="h-96 rounded-xl" />
    </div>
  );
}
