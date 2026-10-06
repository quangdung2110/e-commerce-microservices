"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { LoaderCircle } from "lucide-react";

import { DateTimeInput } from "@/components/admin/date-time-input";
import { FormSection } from "@/components/admin/form-section";
import { PageHeader } from "@/components/admin/page-header";
import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { ErrorState } from "@/components/ui/error-state";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { useToast } from "@/components/ui/toast";
import { promotionKeys } from "@/hooks/use-admin-promotions";
import { useSubmitError } from "@/hooks/use-submit-error";
import { promotionApi } from "@/lib/api/promotion";
import {
  CODE_MAX_LENGTH,
  DESCRIPTION_MAX_LENGTH,
  NAME_MAX_LENGTH,
  campaignCreatePayload,
  emptyCampaignDraft,
  hasErrors,
  normalizeCode,
  validateCampaign,
  type CampaignDraft,
  type CampaignErrors,
} from "@/lib/promotion/campaign";

interface CampaignFieldsProps {
  idPrefix: string;
  draft: CampaignDraft;
  errors: CampaignErrors;
  onChange: (next: CampaignDraft) => void;
  /** Mã chỉ nhập được khi tạo; sau đó là định danh bất biến. */
  codeEditable: boolean;
  disabled?: boolean;
}

export function CampaignFields({ idPrefix, draft, errors, onChange, codeEditable, disabled }: CampaignFieldsProps) {
  return (
    <>
      <div className="grid gap-5 sm:grid-cols-2">
        <div className="space-y-2">
          <Label htmlFor={`${idPrefix}-code`}>Mã campaign *</Label>
          <Input
            id={`${idPrefix}-code`}
            value={draft.code}
            readOnly={!codeEditable}
            disabled={disabled}
            maxLength={CODE_MAX_LENGTH}
            autoComplete="off"
            aria-invalid={errors.code ? true : undefined}
            aria-describedby={errors.code ? `${idPrefix}-code-error` : undefined}
            className="font-mono"
            placeholder="SALE-11-11"
            onChange={(event) => onChange({ ...draft, code: normalizeCode(event.target.value) })}
          />
          {errors.code ? (
            <p id={`${idPrefix}-code-error`} role="alert" className="text-caption text-danger">{errors.code}</p>
          ) : (
            <p className="text-caption text-muted-foreground">
              {codeEditable ? "Chữ HOA, số, gạch dưới hoặc gạch ngang. Không đổi được sau khi tạo." : "Mã không đổi được."}
            </p>
          )}
        </div>
        <div className="space-y-2">
          <Label htmlFor={`${idPrefix}-name`}>Tên campaign *</Label>
          <Input
            id={`${idPrefix}-name`}
            value={draft.name}
            disabled={disabled}
            maxLength={NAME_MAX_LENGTH}
            aria-invalid={errors.name ? true : undefined}
            aria-describedby={errors.name ? `${idPrefix}-name-error` : undefined}
            onChange={(event) => onChange({ ...draft, name: event.target.value })}
          />
          {errors.name ? (
            <p id={`${idPrefix}-name-error`} role="alert" className="text-caption text-danger">{errors.name}</p>
          ) : null}
        </div>
      </div>

      <div className="space-y-2">
        <Label htmlFor={`${idPrefix}-description`}>Mô tả</Label>
        <Textarea
          id={`${idPrefix}-description`}
          value={draft.description}
          disabled={disabled}
          maxLength={DESCRIPTION_MAX_LENGTH}
          aria-invalid={errors.description ? true : undefined}
          onChange={(event) => onChange({ ...draft, description: event.target.value })}
        />
        {errors.description ? (
          <p role="alert" className="text-caption text-danger">{errors.description}</p>
        ) : null}
      </div>

      <div className="grid gap-5 sm:grid-cols-2">
        <div className="space-y-2">
          <Label htmlFor={`${idPrefix}-start`}>Bắt đầu *</Label>
          <DateTimeInput
            id={`${idPrefix}-start`}
            value={draft.startTime}
            disabled={disabled}
            aria-invalid={errors.schedule ? true : undefined}
            aria-describedby={errors.schedule ? `${idPrefix}-schedule-error` : undefined}
            onValueChange={(startTime) => onChange({ ...draft, startTime })}
          />
        </div>
        <div className="space-y-2">
          <Label htmlFor={`${idPrefix}-end`}>Kết thúc *</Label>
          <DateTimeInput
            id={`${idPrefix}-end`}
            value={draft.endTime}
            disabled={disabled}
            aria-invalid={errors.schedule ? true : undefined}
            aria-describedby={errors.schedule ? `${idPrefix}-schedule-error` : undefined}
            onValueChange={(endTime) => onChange({ ...draft, endTime })}
          />
        </div>
      </div>
      {errors.schedule ? (
        <p id={`${idPrefix}-schedule-error`} role="alert" className="text-caption text-danger">{errors.schedule}</p>
      ) : (
        <p className="text-caption text-muted-foreground">
          Giờ Việt Nam (GMT+7). Khoảng chạy là [bắt đầu, kết thúc): đúng thời điểm kết thúc campaign đã đóng.
        </p>
      )}
    </>
  );
}

export function CampaignCreateForm() {
  const router = useRouter();
  const queryClient = useQueryClient();
  const { addToast } = useToast();
  const submitError = useSubmitError();
  const [draft, setDraft] = useState<CampaignDraft>(emptyCampaignDraft);
  const [errors, setErrors] = useState<CampaignErrors>({});

  const mutation = useMutation({
    mutationFn: () => promotionApi.createCampaign(campaignCreatePayload(draft)),
    onSuccess: async (created) => {
      await queryClient.invalidateQueries({ queryKey: promotionKeys.all });
      addToast({ type: "success", title: "Đã tạo campaign nháp" });
      router.replace(`/admin/promotions/${encodeURIComponent(created.data.id)}`);
    },
  });

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    submitError.reset();
    const found = validateCampaign(draft, { checkCode: true });
    setErrors(found);
    if (hasErrors(found)) return;
    try {
      await mutation.mutateAsync();
    } catch (error) {
      submitError.capture(error);
    }
  }

  return (
    <div className="space-y-8">
      <PageHeader
        title="Tạo campaign"
        description="Campaign mới luôn ở trạng thái nháp. Thêm voucher và giảm giá theo sản phẩm rồi mới xuất bản."
      />
      <form onSubmit={submit} className="space-y-6" noValidate>
        {submitError.message ? <ErrorState error={mutation.error} message={submitError.message} /> : null}
        <Alert variant="info">
          Nếu mất kết nối ngay lúc bấm Tạo, hãy kiểm tra danh sách campaign trước khi tạo lại: mã trùng sẽ bị từ chối nên không bị tạo hai lần.
        </Alert>
        <FormSection title="Thông tin và lịch chạy">
          <CampaignFields
            idPrefix="create-campaign"
            draft={draft}
            errors={errors}
            onChange={setDraft}
            codeEditable
            disabled={mutation.isPending}
          />
        </FormSection>
        <div className="flex justify-end gap-3 border-t border-border pt-5">
          <Button type="button" variant="secondary" disabled={mutation.isPending} onClick={() => router.push("/admin/promotions")}>
            Huỷ
          </Button>
          <Button type="submit" disabled={mutation.isPending}>
            {mutation.isPending ? <LoaderCircle className="animate-spin" aria-hidden /> : null}
            {mutation.isPending ? "Đang tạo" : "Tạo campaign"}
          </Button>
        </div>
      </form>
    </div>
  );
}
