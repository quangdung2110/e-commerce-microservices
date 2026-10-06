"use client";

import { useMemo, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowLeft, Gift, LoaderCircle } from "lucide-react";

import { DateTimeInput } from "@/components/admin/date-time-input";
import { FormSection } from "@/components/admin/form-section";
import { IdChip } from "@/components/admin/id-chip";
import { MoneyInput } from "@/components/admin/money-input";
import { PageHeader } from "@/components/admin/page-header";
import { DiscountFields } from "@/components/admin/promotions/discount-fields";
import { StaleAlert, useNowMs } from "@/components/admin/promotions/promotion-common";
import { RuleBuilder } from "@/components/admin/promotions/rule-builder";
import { StatusBadge } from "@/components/admin/status-badge";
import { StickySaveBar } from "@/components/admin/sticky-save-bar";
import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { ErrorState } from "@/components/ui/error-state";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { NativeSelect } from "@/components/ui/native-select";
import { Skeleton } from "@/components/ui/skeleton";
import { Switch } from "@/components/ui/switch";
import { Textarea } from "@/components/ui/textarea";
import { useToast } from "@/components/ui/toast";
import {
  promotionKeys,
  refreshPromotionData,
  useCampaign,
  usePromotion,
} from "@/hooks/use-admin-promotions";
import { useSubmitError } from "@/hooks/use-submit-error";
import { isStaleVersion, type Versioned } from "@/lib/api/etag";
import { productApi } from "@/lib/api/product";
import { promotionApi, type Campaign, type Promotion } from "@/lib/api/promotion";
import { formatDateTime, formatVND } from "@/lib/format";
import {
  CAMPAIGN_PUBLICATION_LABELS,
  DISTRIBUTION_MODE_HINTS,
  DISTRIBUTION_MODE_LABELS,
  VALIDITY_MODE_LABELS,
  WALLET_STATUS_LABELS,
} from "@/lib/labels/promotion";
import {
  CODE_MAX_LENGTH,
  DESCRIPTION_MAX_LENGTH,
  NAME_MAX_LENGTH,
  hasErrors,
  normalizeCode,
} from "@/lib/promotion/campaign";
import { isUuid } from "@/lib/promotion/rule";
import {
  DURATION_UNITS,
  MAX_CLAIM_POOL,
  emptyVoucherDraft,
  validateVoucher,
  voucherCreatePayload,
  voucherPatch,
  voucherToDraft,
  type DurationUnit,
  type VoucherDraft,
  type VoucherMode,
} from "@/lib/promotion/voucher";

const VOUCHER_MODES = Object.keys(DISTRIBUTION_MODE_HINTS) as VoucherMode[];
const validityModes = Object.keys(VALIDITY_MODE_LABELS) as VoucherDraft["validity"]["mode"][];

const campaignHref = (campaignId: string) => `/admin/promotions/${encodeURIComponent(campaignId)}`;
const voucherHref = (campaignId: string, promotionId: string) =>
  `${campaignHref(campaignId)}/vouchers/${encodeURIComponent(promotionId)}`;

function useCategories() {
  return useQuery({
    queryKey: ["product", "categories"],
    queryFn: ({ signal }) => productApi.listCategories({}, signal),
    staleTime: 60_000,
  });
}

function BackToCampaign({ campaignId }: { campaignId: string }) {
  return (
    <Button variant="ghost" size="sm" nativeButton={false} render={<Link href={`${campaignHref(campaignId)}?tab=vouchers`} />} className="-ml-3">
      <ArrowLeft aria-hidden />
      Về campaign
    </Button>
  );
}

/* ─────────────────────────── Tạo voucher ─────────────────────────── */

export function VoucherCreate({ campaignId }: { campaignId: string }) {
  const campaignQuery = useCampaign(campaignId);

  if (campaignQuery.isPending) return <EditorSkeleton />;
  if (campaignQuery.isError || !campaignQuery.data) {
    return <ErrorState error={campaignQuery.error} onRetry={() => void campaignQuery.refetch()} />;
  }

  const campaign = campaignQuery.data.data;
  return (
    <div className="space-y-8">
      <div className="space-y-3">
        <BackToCampaign campaignId={campaignId} />
        <PageHeader title="Thêm voucher" description={`Trong campaign “${campaign.name}”. Voucher áp dụng cho toàn đơn hàng và mỗi đơn dùng tối đa một voucher.`} />
      </div>
      {campaign.publication_status !== "DRAFT" ? (
        <Alert variant="warning" title="Campaign không còn là bản nháp">
          Voucher chỉ thêm được vào campaign nháp. Hãy nhân bản campaign để tạo bản nháp mới rồi chỉnh sửa.
        </Alert>
      ) : (
        <VoucherEditor campaign={campaignQuery.data} onReloadCampaign={() => campaignQuery.refetch()} />
      )}
    </div>
  );
}

/* ─────────────────────────── Chi tiết voucher ─────────────────────────── */

export function VoucherDetail({ promotionId }: { promotionId: string }) {
  const promotionQuery = usePromotion(promotionId);
  const campaignId = promotionQuery.data?.data.campaign_id ?? "";
  const campaignQuery = useCampaign(campaignId);

  if (promotionQuery.isPending || (Boolean(campaignId) && campaignQuery.isPending)) return <EditorSkeleton />;
  if (promotionQuery.isError || !promotionQuery.data) {
    return <ErrorState error={promotionQuery.error} onRetry={() => void promotionQuery.refetch()} />;
  }
  if (campaignQuery.isError || !campaignQuery.data) {
    return <ErrorState error={campaignQuery.error} onRetry={() => void campaignQuery.refetch()} />;
  }

  const promotion = promotionQuery.data;
  const campaign = campaignQuery.data.data;
  const editable = campaign.publication_status === "DRAFT";

  return (
    <div className="space-y-8">
      <div className="space-y-3">
        <BackToCampaign campaignId={campaign.id} />
        <PageHeader title={promotion.data.name} description={`Campaign “${campaign.name}” · cập nhật ${formatDateTime(promotion.data.updated_at)}`} />
      </div>

      <div className="flex flex-wrap items-center gap-3">
        <StatusBadge value={promotion.data.distribution_mode} labels={DISTRIBUTION_MODE_LABELS} />
        <StatusBadge value={campaign.publication_status} labels={CAMPAIGN_PUBLICATION_LABELS} />
        <IdChip value={promotion.data.id} label="Sao chép mã voucher" />
      </div>

      {editable ? null : (
        <Alert variant="info" title="Voucher đã xuất bản nên không sửa được">
          Nội dung voucher bất biến sau khi campaign xuất bản. Muốn đổi, hãy nhân bản campaign thành bản nháp mới.
        </Alert>
      )}

      <VoucherUsage budget={promotion.data.budget} />

      <VoucherEditor
        key={`${promotion.data.id}:${promotion.etag ?? ""}`}
        campaign={campaignQuery.data}
        promotion={promotion}
        readOnly={!editable}
        onReloadCampaign={async () => {
          await Promise.all([promotionQuery.refetch(), campaignQuery.refetch()]);
        }}
      />

      {promotion.data.distribution_mode === "DIRECT_GRANT" ? (
        <GrantVoucher promotion={promotion.data} campaign={campaign} />
      ) : null}
    </div>
  );
}

/* ─────────────────────────── Form dùng chung ─────────────────────────── */

interface VoucherEditorProps {
  campaign: Versioned<Campaign>;
  promotion?: Versioned<Promotion>;
  readOnly?: boolean;
  onReloadCampaign: () => void | Promise<unknown>;
}

function VoucherEditor({ campaign, promotion, readOnly = false, onReloadCampaign }: VoucherEditorProps) {
  const router = useRouter();
  const queryClient = useQueryClient();
  const { addToast } = useToast();
  const submitError = useSubmitError();
  const categoriesQuery = useCategories();
  const isCreate = !promotion;

  const [draft, setDraft] = useState<VoucherDraft>(() =>
    promotion ? voucherToDraft(promotion.data) : emptyVoucherDraft()
  );
  const [showErrors, setShowErrors] = useState(false);
  const [stale, setStale] = useState(false);

  const errors = useMemo(
    () => validateVoucher(draft, { checkCode: isCreate, campaignStart: campaign.data.start_time }),
    [draft, isCreate, campaign.data.start_time]
  );
  const visible = showErrors ? errors : { rule: [] as string[] };
  const dirty = promotion
    ? JSON.stringify(voucherToDraft(promotion.data)) !== JSON.stringify(draft)
    : false;
  const etag = promotion ? promotion.etag : campaign.etag;

  const mutation = useMutation({
    mutationFn: async () => {
      if (!etag) throw new Error("MISSING_ETAG");
      if (promotion) {
        const body = voucherPatch(promotion.data, draft);
        if (!body) return null;
        return promotionApi.updatePromotion(promotion.data.id, etag, body);
      }
      return promotionApi.createPromotion(campaign.data.id, etag, voucherCreatePayload(draft));
    },
    onSuccess: async (saved) => {
      await refreshPromotionData(queryClient);
      if (!saved) return;
      addToast({ type: "success", title: isCreate ? "Đã thêm voucher" : "Đã lưu voucher" });
      if (isCreate) {
        router.replace(voucherHref(campaign.data.id, saved.data.id));
      } else {
        queryClient.setQueryData(promotionKeys.voucher(saved.data.id), saved);
      }
    },
  });

  async function save() {
    submitError.reset();
    setShowErrors(true);
    if (hasErrors(errors)) return;
    try {
      await mutation.mutateAsync();
      setStale(false);
    } catch (error) {
      setStale(isStaleVersion(error));
      submitError.capture(error);
    }
  }

  function discard() {
    if (promotion) setDraft(voucherToDraft(promotion.data));
    setShowErrors(false);
    submitError.reset();
  }

  const disabled = readOnly || mutation.isPending;
  const set = (next: Partial<VoucherDraft>) => setDraft((current) => ({ ...current, ...next }));
  const heading = (id: string) => `voucher-${id}`;

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        void save();
      }}
      className="space-y-6"
      noValidate
    >
      {submitError.message ? <ErrorState error={mutation.error} message={submitError.message} /> : null}
      {stale ? <StaleAlert onReload={async () => { setStale(false); await onReloadCampaign(); }} /> : null}
      {!etag ? <Alert variant="warning">Response không có ETag nên lệnh lưu đang bị khoá. Hãy tải lại dữ liệu.</Alert> : null}

      <FormSection title="Thông tin voucher">
        <div className="grid gap-5 sm:grid-cols-2">
          <div className="space-y-2">
            <Label htmlFor={heading("code")}>Mã voucher *</Label>
            <Input
              id={heading("code")}
              value={draft.code}
              readOnly={!isCreate}
              disabled={disabled && isCreate}
              maxLength={CODE_MAX_LENGTH}
              autoComplete="off"
              className="font-mono"
              aria-invalid={visible.code ? true : undefined}
              placeholder="GIAM10"
              onChange={(event) => set({ code: normalizeCode(event.target.value) })}
            />
            {visible.code ? (
              <p role="alert" className="text-caption text-danger">{visible.code}</p>
            ) : (
              <p className="text-caption text-muted-foreground">{isCreate ? "Duy nhất toàn hệ thống, không đổi được sau khi tạo." : "Mã không đổi được."}</p>
            )}
          </div>
          <div className="space-y-2">
            <Label htmlFor={heading("name")}>Tên voucher *</Label>
            <Input
              id={heading("name")}
              value={draft.name}
              disabled={disabled}
              maxLength={NAME_MAX_LENGTH}
              aria-invalid={visible.name ? true : undefined}
              onChange={(event) => set({ name: event.target.value })}
            />
            {visible.name ? <p role="alert" className="text-caption text-danger">{visible.name}</p> : null}
          </div>
        </div>
        <div className="space-y-2">
          <Label htmlFor={heading("description")}>Mô tả</Label>
          <Textarea
            id={heading("description")}
            value={draft.description}
            disabled={disabled}
            maxLength={DESCRIPTION_MAX_LENGTH}
            onChange={(event) => set({ description: event.target.value })}
          />
        </div>
        <div className="flex items-start justify-between gap-6 rounded-lg border border-border bg-surface-2 p-4">
          <div className="space-y-1">
            <Label htmlFor={heading("enabled")}>Cho phép sử dụng</Label>
            <p className="text-caption text-muted-foreground">Tắt để giữ voucher trong campaign nhưng không ai nhận hay dùng được.</p>
          </div>
          <Switch id={heading("enabled")} checked={draft.enabled} disabled={disabled} onCheckedChange={(enabled) => set({ enabled })} />
        </div>
      </FormSection>

      <FormSection title="Cách phát hành và mức giảm">
        <div className="space-y-2 sm:max-w-md">
          <Label htmlFor={heading("mode")}>Cách phát hành *</Label>
          <NativeSelect
            id={heading("mode")}
            value={draft.mode}
            disabled={disabled}
            onChange={(event) => set({ mode: event.target.value as VoucherMode })}
          >
            {VOUCHER_MODES.map((mode) => (
              <option key={mode} value={mode}>{DISTRIBUTION_MODE_LABELS[mode].text}</option>
            ))}
          </NativeSelect>
          <p className="text-caption text-muted-foreground">{DISTRIBUTION_MODE_HINTS[draft.mode]}</p>
        </div>
        <DiscountFields idPrefix={heading("discount")} value={draft.discount} disabled={disabled} error={visible.discount} onChange={(discount) => set({ discount })} />
        <div className="space-y-2 sm:max-w-md">
          <Label htmlFor={heading("min-order")}>Giá trị đơn tối thiểu *</Label>
          <MoneyInput
            id={heading("min-order")}
            value={draft.minOrder}
            disabled={disabled}
            aria-invalid={visible.minOrder ? true : undefined}
            onValueChange={(minOrder) => set({ minOrder })}
          />
          {visible.minOrder ? (
            <p role="alert" className="text-caption text-danger">{visible.minOrder}</p>
          ) : (
            <p className="text-caption text-muted-foreground">Tính trên tổng tiền hàng trước giảm giá. Nhập 0 nếu không yêu cầu.</p>
          )}
        </div>
      </FormSection>

      <FormSection
        title="Điều kiện áp dụng"
        description="Điều kiện bổ sung ngoài giá trị đơn tối thiểu. Backend đánh giá lại khi người mua thanh toán."
      >
        <RuleBuilder
          value={draft.rule}
          onChange={(rule) => set({ rule })}
          categories={categoriesQuery.data ?? []}
          categoriesError={categoriesQuery.isError}
          disabled={disabled}
          errors={visible.rule}
        />
      </FormSection>

      <FormSection title="Hạn dùng" description="Hạn được chốt vào ví của người mua lúc họ lưu voucher; kết thúc campaign không thu hồi voucher đã lưu.">
        <div className="grid gap-5 sm:grid-cols-2">
          <div className="space-y-2">
            <Label htmlFor={heading("validity-mode")}>Tính hạn dùng *</Label>
            <NativeSelect
              id={heading("validity-mode")}
              value={draft.validity.mode}
              disabled={disabled}
              onChange={(event) =>
                set({ validity: { ...draft.validity, mode: event.target.value as VoucherDraft["validity"]["mode"] } })
              }
            >
              {validityModes.map((mode) => (
                <option key={mode} value={mode}>{VALIDITY_MODE_LABELS[mode]}</option>
              ))}
            </NativeSelect>
          </div>
          {draft.validity.mode === "DURATION_AFTER_SAVE" ? (
            <div className="grid grid-cols-[minmax(0,1fr)_8rem] gap-3">
              <div className="space-y-2">
                <Label htmlFor={heading("duration")}>Thời lượng *</Label>
                <Input
                  id={heading("duration")}
                  type="number"
                  inputMode="numeric"
                  min={1}
                  step={1}
                  value={draft.validity.duration ?? ""}
                  disabled={disabled}
                  onChange={(event) => {
                    const parsed = Number.parseInt(event.target.value, 10);
                    set({ validity: { ...draft.validity, duration: Number.isFinite(parsed) ? parsed : undefined } });
                  }}
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor={heading("duration-unit")}>Đơn vị</Label>
                <NativeSelect
                  id={heading("duration-unit")}
                  value={draft.validity.unit}
                  disabled={disabled}
                  onChange={(event) => set({ validity: { ...draft.validity, unit: event.target.value as DurationUnit } })}
                >
                  {(Object.keys(DURATION_UNITS) as DurationUnit[]).map((unit) => (
                    <option key={unit} value={unit}>{DURATION_UNITS[unit].label}</option>
                  ))}
                </NativeSelect>
              </div>
            </div>
          ) : null}
          {draft.validity.mode === "FIXED_DATE" ? (
            <div className="space-y-2">
              <Label htmlFor={heading("redeem-until")}>Hết hạn lúc *</Label>
              <DateTimeInput
                id={heading("redeem-until")}
                value={draft.validity.redeemUntil}
                disabled={disabled}
                onValueChange={(redeemUntil) => set({ validity: { ...draft.validity, redeemUntil } })}
              />
              <p className="text-caption text-muted-foreground">Giờ Việt Nam (GMT+7).</p>
            </div>
          ) : null}
        </div>
        {visible.validity ? <p role="alert" className="text-caption text-danger">{visible.validity}</p> : null}
      </FormSection>

      <FormSection title="Giới hạn" description="Để trống ô nào thì không giới hạn theo tiêu chí đó.">
        <div className="grid gap-5 sm:grid-cols-3">
          {draft.mode === "LIMITED_CLAIM" ? (
            <div className="space-y-2">
              <Label htmlFor={heading("claim-quantity")}>Số suất nhận *</Label>
              <Input
                id={heading("claim-quantity")}
                type="number"
                inputMode="numeric"
                min={1}
                max={MAX_CLAIM_POOL}
                step={1}
                value={draft.budget.claimQuantity ?? ""}
                disabled={disabled}
                onChange={(event) => {
                  const parsed = Number.parseInt(event.target.value, 10);
                  set({ budget: { ...draft.budget, claimQuantity: Number.isFinite(parsed) ? parsed : undefined } });
                }}
              />
            </div>
          ) : null}
          <div className="space-y-2">
            <Label htmlFor={heading("redemption-quantity")}>Số lượt dùng tối đa</Label>
            <Input
              id={heading("redemption-quantity")}
              type="number"
              inputMode="numeric"
              min={1}
              step={1}
              value={draft.budget.redemptionQuantity ?? ""}
              disabled={disabled}
              onChange={(event) => {
                const parsed = Number.parseInt(event.target.value, 10);
                set({ budget: { ...draft.budget, redemptionQuantity: Number.isFinite(parsed) ? parsed : undefined } });
              }}
            />
          </div>
          <div className="space-y-2">
            <Label htmlFor={heading("total-budget")}>Ngân sách giảm tối đa</Label>
            <MoneyInput
              id={heading("total-budget")}
              value={draft.budget.totalBudget}
              disabled={disabled}
              onValueChange={(totalBudget) => set({ budget: { ...draft.budget, totalBudget } })}
            />
          </div>
        </div>
        {visible.budget ? <p role="alert" className="text-caption text-danger">{visible.budget}</p> : null}
        {draft.mode === "LIMITED_CLAIM" ? (
          <p className="text-caption text-muted-foreground">Các suất nhận được sinh sẵn khi campaign xuất bản nên không đổi được số suất sau đó.</p>
        ) : null}
      </FormSection>

      {readOnly ? null : isCreate ? (
        <div className="flex justify-end gap-3 border-t border-border pt-5">
          <Button type="button" variant="secondary" disabled={mutation.isPending} onClick={() => router.push(campaignHref(campaign.data.id))}>
            Huỷ
          </Button>
          <Button type="submit" disabled={mutation.isPending || !etag}>
            {mutation.isPending ? <LoaderCircle className="animate-spin" aria-hidden /> : null}
            {mutation.isPending ? "Đang thêm" : "Thêm voucher"}
          </Button>
        </div>
      ) : (
        <StickySaveBar isDirty={dirty} isSaving={mutation.isPending} onSave={() => void save()} onDiscard={discard} />
      )}
    </form>
  );
}

/* ─────────────────────────── Mức sử dụng ─────────────────────────── */

function VoucherUsage({ budget }: { budget: Promotion["budget"] }) {
  const cells: Array<{ label: string; value: string }> = [
    {
      label: "Đã nhận",
      value: budget.total_claim_quantity === null ? budget.claimed_quantity.toLocaleString("vi-VN") : `${budget.claimed_quantity.toLocaleString("vi-VN")} / ${budget.total_claim_quantity.toLocaleString("vi-VN")}`,
    },
    {
      label: "Đã dùng",
      value: budget.total_redemption_quantity === null ? budget.used_quantity.toLocaleString("vi-VN") : `${budget.used_quantity.toLocaleString("vi-VN")} / ${budget.total_redemption_quantity.toLocaleString("vi-VN")}`,
    },
    {
      label: "Ngân sách đã giảm",
      value: budget.total_budget === null ? formatVND(budget.used_budget.amount_minor) : `${formatVND(budget.used_budget.amount_minor)} / ${formatVND(budget.total_budget.amount_minor)}`,
    },
  ];
  return (
    <section aria-label="Mức sử dụng voucher" className="rounded-xl border border-border bg-surface-overlay p-6">
      <dl className="grid gap-5 sm:grid-cols-3">
        {cells.map((cell) => (
          <div key={cell.label}>
            <dt className="text-caption text-muted-foreground">{cell.label}</dt>
            <dd className="mt-1 text-headline-5 font-serif font-normal tabular-nums">{cell.value}</dd>
          </div>
        ))}
      </dl>
      <p className="mt-4 text-caption text-muted-foreground">Cập nhật {formatDateTime(budget.updated_at)}. Số liệu là bản ghi bền vững, có thể chậm hơn lượt đang giữ chỗ khi thanh toán.</p>
    </section>
  );
}

/* ─────────────────────────── Cấp voucher trực tiếp ─────────────────────────── */

function GrantVoucher({ promotion, campaign }: { promotion: Promotion; campaign: Campaign }) {
  const [open, setOpen] = useState(false);
  const [granted, setGranted] = useState<{ userId: string; status: string; expiredAt: string; created: boolean } | null>(null);
  const now = useNowMs();
  const endsAt = new Date(campaign.end_time).getTime();
  const reason =
    campaign.publication_status !== "PUBLISHED"
      ? "Campaign cần được xuất bản trước khi cấp voucher."
      : Number.isFinite(endsAt) && endsAt <= now
        ? "Campaign đã kết thúc nên không cấp thêm được."
        : !promotion.is_enabled
          ? "Voucher đang tắt. Voucher đã xuất bản không bật lại được; hãy nhân bản campaign."
          : null;

  return (
    <FormSection title="Cấp voucher cho người dùng" description="Voucher cấp trực tiếp vào ví của một người dùng. Mỗi người chỉ nhận một lần; cấp lại trả về ví hiện có và không kéo dài hạn.">
      {reason ? <Alert variant="info">{reason}</Alert> : null}
      {granted ? (
        <Alert variant="success" title={granted.created ? "Đã cấp voucher" : "Người dùng đã có voucher này"}>
          <span className="font-mono">{granted.userId}</span> · {granted.status} · hạn {formatDateTime(granted.expiredAt)}
        </Alert>
      ) : null}
      <div>
        <Button type="button" variant="outline" disabled={Boolean(reason)} onClick={() => setOpen(true)}>
          <Gift aria-hidden />
          Cấp voucher
        </Button>
      </div>
      {open ? (
        <GrantDialog
          promotionId={promotion.id}
          onOpenChange={setOpen}
          onGranted={(result) => setGranted(result)}
        />
      ) : null}
    </FormSection>
  );
}

function GrantDialog({
  promotionId,
  onOpenChange,
  onGranted,
}: {
  promotionId: string;
  onOpenChange: (open: boolean) => void;
  onGranted: (result: { userId: string; status: string; expiredAt: string; created: boolean }) => void;
}) {
  const { addToast } = useToast();
  const submitError = useSubmitError();
  const [userId, setUserId] = useState("");
  const [reason, setReason] = useState("");
  const [validation, setValidation] = useState<string | null>(null);

  const mutation = useMutation({
    mutationFn: () => promotionApi.grantVoucher(promotionId, { user_id: userId.trim().toLowerCase(), reason: reason.trim() }),
    onSuccess: ({ wallet, created }) => {
      onGranted({
        userId: wallet.user_id,
        status: WALLET_STATUS_LABELS[wallet.status]?.text ?? wallet.status,
        expiredAt: wallet.expired_at,
        created,
      });
      addToast({ type: "success", title: created ? "Đã cấp voucher" : "Người dùng đã có voucher này" });
      onOpenChange(false);
    },
  });

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    submitError.reset();
    const invalid = !isUuid(userId.trim())
      ? "Mã người dùng phải là UUID hợp lệ."
      : !reason.trim()
        ? "Hãy nhập lý do cấp voucher."
        : null;
    setValidation(invalid);
    if (invalid) return;
    try {
      await mutation.mutateAsync();
    } catch (error) {
      submitError.capture(error, {
        overrides: {
          ALREADY_USED_VOUCHER: "Người dùng này đã dùng voucher này rồi nên không cấp lại được.",
          VOUCHER_EXPIRED: "Voucher trong ví người dùng này đã hết hạn nên không cấp lại được.",
          CAMPAIGN_NOT_ACTIVE: "Chỉ cấp được khi campaign đã xuất bản, chưa kết thúc và voucher đang bật.",
          DISTRIBUTION_MODE_MISMATCH: "Chỉ voucher cấp trực tiếp mới cấp được cho từng người dùng.",
          OUT_OF_STOCK: "Voucher đã hết số suất.",
        },
      });
    }
  }

  return (
    <Dialog open onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Cấp voucher</DialogTitle>
          <DialogDescription>Backend chưa có API tìm người dùng, nên cần dán mã người dùng (UUID) của tài khoản.</DialogDescription>
        </DialogHeader>
        <form onSubmit={submit} className="mt-6 space-y-5" noValidate>
          {validation ? <Alert variant="danger">{validation}</Alert> : null}
          {submitError.message ? <ErrorState error={mutation.error} message={submitError.message} /> : null}
          <div className="space-y-2">
            <Label htmlFor="grant-user">Mã người dùng *</Label>
            <Input id="grant-user" value={userId} autoComplete="off" className="font-mono" disabled={mutation.isPending} onChange={(event) => setUserId(event.target.value)} />
          </div>
          <div className="space-y-2">
            <Label htmlFor="grant-reason">Lý do *</Label>
            <Textarea id="grant-reason" value={reason} maxLength={500} disabled={mutation.isPending} placeholder="Ví dụ: bồi thường đơn trễ #123" onChange={(event) => setReason(event.target.value)} />
          </div>
          <DialogFooter>
            <Button type="button" variant="secondary" disabled={mutation.isPending} onClick={() => onOpenChange(false)}>Huỷ</Button>
            <Button type="submit" disabled={mutation.isPending}>
              {mutation.isPending ? <LoaderCircle className="animate-spin" aria-hidden /> : null}
              {mutation.isPending ? "Đang cấp" : "Cấp voucher"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

function EditorSkeleton() {
  return (
    <div className="space-y-8" aria-busy="true" aria-label="Đang tải voucher">
      <div className="space-y-3"><Skeleton className="h-10 w-72" /><Skeleton className="h-4 w-80 max-w-full" /></div>
      <Skeleton className="h-64 rounded-xl" />
      <Skeleton className="h-80 rounded-xl" />
    </div>
  );
}
