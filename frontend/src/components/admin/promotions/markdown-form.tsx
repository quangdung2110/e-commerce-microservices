"use client";

import { useMemo, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { ArrowLeft, LoaderCircle } from "lucide-react";

import { FormSection } from "@/components/admin/form-section";
import { IdChip } from "@/components/admin/id-chip";
import { PageHeader } from "@/components/admin/page-header";
import { DiscountFields } from "@/components/admin/promotions/discount-fields";
import { ProductPicker } from "@/components/admin/promotions/product-picker";
import { ProductName, StaleAlert } from "@/components/admin/promotions/promotion-common";
import { StatusBadge } from "@/components/admin/status-badge";
import { StickySaveBar } from "@/components/admin/sticky-save-bar";
import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { ErrorState } from "@/components/ui/error-state";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";
import { Switch } from "@/components/ui/switch";
import { useToast } from "@/components/ui/toast";
import {
  promotionKeys,
  refreshPromotionData,
  useCampaign,
  useMarkdownRule,
} from "@/hooks/use-admin-promotions";
import { useSubmitError } from "@/hooks/use-submit-error";
import { isStaleVersion, type Versioned } from "@/lib/api/etag";
import { promotionApi, type Campaign, type MarkdownRule } from "@/lib/api/promotion";
import { formatDateTime } from "@/lib/format";
import { CAMPAIGN_PUBLICATION_LABELS } from "@/lib/labels/promotion";
import {
  PRIORITY_MAX,
  PRIORITY_MIN,
  hasErrors,
  markdownCreatePayload,
  markdownPatch,
  markdownToDraft,
  validateMarkdown,
  type MarkdownDraft,
} from "@/lib/promotion/campaign";
import { emptyDiscountDraft } from "@/lib/promotion/discount";

const campaignHref = (campaignId: string) => `/admin/promotions/${encodeURIComponent(campaignId)}`;

function BackToCampaign({ campaignId }: { campaignId: string }) {
  return (
    <Button variant="ghost" size="sm" nativeButton={false} render={<Link href={`${campaignHref(campaignId)}?tab=markdowns`} />} className="-ml-3">
      <ArrowLeft aria-hidden />
      Về campaign
    </Button>
  );
}

export function MarkdownCreate({ campaignId }: { campaignId: string }) {
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
        <PageHeader title="Thêm giảm giá theo sản phẩm" description={`Trong campaign “${campaign.name}”. Giá bán của sản phẩm giảm trực tiếp khi campaign đang diễn ra, không cần voucher.`} />
      </div>
      {campaign.publication_status !== "DRAFT" ? (
        <Alert variant="warning" title="Campaign không còn là bản nháp">
          Chỉ thêm được vào campaign nháp. Hãy nhân bản campaign để tạo bản nháp mới rồi chỉnh sửa.
        </Alert>
      ) : (
        <MarkdownEditor campaign={campaignQuery.data} onReload={() => campaignQuery.refetch()} />
      )}
    </div>
  );
}

export function MarkdownDetail({ ruleId }: { ruleId: string }) {
  const ruleQuery = useMarkdownRule(ruleId);
  const campaignId = ruleQuery.data?.data.campaign_id ?? "";
  const campaignQuery = useCampaign(campaignId);

  if (ruleQuery.isPending || (Boolean(campaignId) && campaignQuery.isPending)) return <EditorSkeleton />;
  if (ruleQuery.isError || !ruleQuery.data) {
    return <ErrorState error={ruleQuery.error} onRetry={() => void ruleQuery.refetch()} />;
  }
  if (campaignQuery.isError || !campaignQuery.data) {
    return <ErrorState error={campaignQuery.error} onRetry={() => void campaignQuery.refetch()} />;
  }

  const rule = ruleQuery.data;
  const campaign = campaignQuery.data.data;
  const editable = campaign.publication_status === "DRAFT";
  return (
    <div className="space-y-8">
      <div className="space-y-3">
        <BackToCampaign campaignId={campaign.id} />
        <PageHeader title="Giảm giá theo sản phẩm" description={`Campaign “${campaign.name}” · cập nhật ${formatDateTime(rule.data.updated_at)}`} />
      </div>
      <div className="flex flex-wrap items-center gap-3">
        <StatusBadge value={campaign.publication_status} labels={CAMPAIGN_PUBLICATION_LABELS} />
        <IdChip value={rule.data.id} label="Sao chép mã markdown" />
      </div>
      {editable ? null : (
        <Alert variant="info" title="Campaign đã xuất bản nên không sửa được">
          Muốn đổi, hãy nhân bản campaign thành bản nháp mới.
        </Alert>
      )}
      {rule.data.invalidated_at ? (
        <Alert variant="warning" title="Quy tắc đã bị vô hiệu hoá">
          {rule.data.invalidation_reason === "PRODUCT_DELETED"
            ? "Sản phẩm đã bị xoá khỏi catalog"
            : "Quy tắc không còn hiệu lực"}{" "}
          từ {formatDateTime(rule.data.invalidated_at)}, nên không còn tạo giá giảm.
        </Alert>
      ) : null}
      <MarkdownEditor
        key={`${rule.data.id}:${rule.etag ?? ""}`}
        campaign={campaignQuery.data}
        rule={rule}
        readOnly={!editable}
        onReload={async () => {
          await Promise.all([ruleQuery.refetch(), campaignQuery.refetch()]);
        }}
      />
    </div>
  );
}

interface MarkdownEditorProps {
  campaign: Versioned<Campaign>;
  rule?: Versioned<MarkdownRule>;
  readOnly?: boolean;
  onReload: () => void | Promise<unknown>;
}

function MarkdownEditor({ campaign, rule, readOnly = false, onReload }: MarkdownEditorProps) {
  const router = useRouter();
  const queryClient = useQueryClient();
  const { addToast } = useToast();
  const submitError = useSubmitError();
  const isCreate = !rule;
  const [draft, setDraft] = useState<MarkdownDraft>(() =>
    rule
      ? markdownToDraft(rule.data)
      : { productId: "", discount: emptyDiscountDraft(), priority: 0, enabled: true }
  );
  const [showErrors, setShowErrors] = useState(false);
  const [stale, setStale] = useState(false);

  const errors = useMemo(() => validateMarkdown(draft, { checkProduct: isCreate }), [draft, isCreate]);
  const visible = showErrors ? errors : {};
  const etag = rule ? rule.etag : campaign.etag;
  const dirty = rule ? JSON.stringify(markdownToDraft(rule.data)) !== JSON.stringify(draft) : false;

  const mutation = useMutation({
    mutationFn: async () => {
      if (!etag) throw new Error("MISSING_ETAG");
      if (rule) {
        const body = markdownPatch(rule.data, draft);
        return body ? promotionApi.updateMarkdownRule(rule.data.id, etag, body) : null;
      }
      return promotionApi.createMarkdownRule(campaign.data.id, etag, markdownCreatePayload(draft));
    },
    onSuccess: async (saved) => {
      await refreshPromotionData(queryClient);
      if (!saved) return;
      addToast({ type: "success", title: isCreate ? "Đã thêm giảm giá sản phẩm" : "Đã lưu giảm giá sản phẩm" });
      if (isCreate) {
        router.replace(`${campaignHref(campaign.data.id)}/markdowns/${encodeURIComponent(saved.data.id)}`);
      } else {
        queryClient.setQueryData(promotionKeys.markdown(saved.data.id), saved);
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
      submitError.capture(error, {
        overrides: {
          PRODUCT_NOT_FOUND: "Không tìm thấy sản phẩm này trong catalog. Hãy kiểm tra lại mã sản phẩm.",
          PRODUCT_TOMBSTONED: "Sản phẩm này đã bị xoá khỏi catalog nên không thể tạo giảm giá.",
        },
      });
    }
  }

  const disabled = readOnly || mutation.isPending;

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
      {stale ? <StaleAlert onReload={async () => { setStale(false); await onReload(); }} /> : null}
      {!etag ? <Alert variant="warning">Response không có ETag nên lệnh lưu đang bị khoá. Hãy tải lại dữ liệu.</Alert> : null}

      <FormSection title="Sản phẩm" description={isCreate ? "Mỗi sản phẩm chỉ có một quy tắc trong một campaign." : "Sản phẩm không đổi được; muốn áp dụng cho sản phẩm khác, hãy thêm quy tắc mới."}>
        {isCreate ? (
          <ProductPicker value={draft.productId} disabled={disabled} error={visible.product} onChange={(productId) => setDraft((current) => ({ ...current, productId }))} />
        ) : (
          <div className="space-y-2">
            <p className="text-[15px]"><ProductName productId={draft.productId} /></p>
            <IdChip value={draft.productId} label="Sao chép mã sản phẩm" />
          </div>
        )}
      </FormSection>

      <FormSection title="Mức giảm">
        <DiscountFields idPrefix="markdown-discount" value={draft.discount} disabled={disabled} error={visible.discount} onChange={(discount) => setDraft((current) => ({ ...current, discount }))} />
      </FormSection>

      <FormSection title="Ưu tiên và trạng thái" description="Khi một sản phẩm nằm trong nhiều campaign đang diễn ra, quy tắc có độ ưu tiên cao hơn được áp dụng; bằng nhau thì quy tắc tạo trước thắng.">
        <div className="space-y-2 sm:max-w-xs">
          <Label htmlFor="markdown-priority">Độ ưu tiên *</Label>
          <Input
            id="markdown-priority"
            type="number"
            inputMode="numeric"
            step={1}
            min={PRIORITY_MIN}
            max={PRIORITY_MAX}
            value={draft.priority ?? ""}
            disabled={disabled}
            aria-invalid={visible.priority ? true : undefined}
            onChange={(event) => {
              const parsed = Number.parseInt(event.target.value, 10);
              setDraft((current) => ({ ...current, priority: Number.isFinite(parsed) ? parsed : undefined }));
            }}
          />
          {visible.priority ? <p role="alert" className="text-caption text-danger">{visible.priority}</p> : null}
        </div>
        <div className="flex items-start justify-between gap-6 rounded-lg border border-border bg-surface-2 p-4">
          <div className="space-y-1">
            <Label htmlFor="markdown-enabled">Cho phép áp dụng</Label>
            <p className="text-caption text-muted-foreground">Tắt để giữ quy tắc trong campaign nhưng không giảm giá.</p>
          </div>
          <Switch id="markdown-enabled" checked={draft.enabled} disabled={disabled} onCheckedChange={(enabled) => setDraft((current) => ({ ...current, enabled }))} />
        </div>
      </FormSection>

      {readOnly ? null : isCreate ? (
        <div className="flex justify-end gap-3 border-t border-border pt-5">
          <Button type="button" variant="secondary" disabled={mutation.isPending} onClick={() => router.push(campaignHref(campaign.data.id))}>Huỷ</Button>
          <Button type="submit" disabled={mutation.isPending || !etag}>
            {mutation.isPending ? <LoaderCircle className="animate-spin" aria-hidden /> : null}
            {mutation.isPending ? "Đang thêm" : "Thêm giảm giá"}
          </Button>
        </div>
      ) : (
        <StickySaveBar
          isDirty={dirty}
          isSaving={mutation.isPending}
          onSave={() => void save()}
          onDiscard={() => { if (rule) setDraft(markdownToDraft(rule.data)); setShowErrors(false); submitError.reset(); }}
        />
      )}
    </form>
  );
}

function EditorSkeleton() {
  return (
    <div className="space-y-8" aria-busy="true" aria-label="Đang tải giảm giá sản phẩm">
      <div className="space-y-3"><Skeleton className="h-10 w-72" /><Skeleton className="h-4 w-80 max-w-full" /></div>
      <Skeleton className="h-48 rounded-xl" />
      <Skeleton className="h-56 rounded-xl" />
    </div>
  );
}
