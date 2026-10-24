"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";

import { FormSection } from "@/components/admin/form-section";
import { CommandRecovery, FieldBlock, SubmitButton } from "@/components/admin/inventory/inventory-shared";
import { PageHeader } from "@/components/admin/page-header";
import { Alert } from "@/components/ui/alert";
import { ErrorState } from "@/components/ui/error-state";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { NativeSelect } from "@/components/ui/native-select";
import { Switch } from "@/components/ui/switch";
import { Textarea } from "@/components/ui/textarea";
import { useInventoryCommand } from "@/hooks/use-inventory-command";
import { useSubmitError } from "@/hooks/use-submit-error";
import { inventoryAdminApi, type CreateWarehouseRequest } from "@/lib/api/inventory-admin";
import { inventoryErrorMessage } from "@/lib/inventory";

export function WarehouseCreateForm() {
  const router = useRouter();
  const submitError = useSubmitError();
  const [validation, setValidation] = useState<string | null>(null);
  const [name, setName] = useState("");
  const [line1, setLine1] = useState("");
  const [line2, setLine2] = useState("");
  const [locality, setLocality] = useState("");
  const [region, setRegion] = useState("");
  const [postalCode, setPostalCode] = useState("");
  const [countryCode, setCountryCode] = useState("VN");
  const [isActive, setIsActive] = useState(true);
  const [operationalState, setOperationalState] = useState<"NORMAL" | "OUTAGE">("NORMAL");
  const [priority, setPriority] = useState("100");
  const [reason, setReason] = useState("");
  const [evidence, setEvidence] = useState("");

  const create = useInventoryCommand({
    mutationFn: (input: CreateWarehouseRequest, context) =>
      inventoryAdminApi.createWarehouse(input, context.commandId),
    onSuccess: (response) => {
      router.replace(`/admin/inventory/warehouses/${encodeURIComponent(response.warehouse.warehouse_id)}`);
    },
  });

  function edited() {
    if (!create.startNewIntent()) return;
    submitError.reset();
    setValidation(null);
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (create.isPending || create.isRetryDelayed) return;
    submitError.reset();
    setValidation(null);
    const fulfillmentPriority = Number(priority);
    if (!name.trim() || !line1.trim() || !locality.trim() || !reason.trim() || !evidence.trim()) {
      setValidation("Vui lòng nhập tên kho, địa chỉ bắt buộc, lý do và tham chiếu bằng chứng.");
      return;
    }
    if (!/^[A-Z]{2}$/.test(countryCode)) {
      setValidation("Mã quốc gia phải gồm đúng hai chữ cái viết hoa, ví dụ VN.");
      return;
    }
    if (!Number.isInteger(fulfillmentPriority) || fulfillmentPriority < 0) {
      setValidation("Ưu tiên phân bổ phải là số nguyên không âm.");
      return;
    }
    try {
      await create.mutateAsync({
        name: name.trim(),
        address: {
          line1: line1.trim(),
          ...(line2.trim() ? { line2: line2.trim() } : {}),
          locality: locality.trim(),
          ...(region.trim() ? { region: region.trim() } : {}),
          ...(postalCode.trim() ? { postal_code: postalCode.trim() } : {}),
          country_code: countryCode,
        },
        is_active: isActive,
        operational_state: operationalState,
        fulfillment_priority: fulfillmentPriority,
        reason: reason.trim(),
        evidence_reference: evidence.trim(),
      });
    } catch (error) {
      submitError.capture(error, { overrides: { FORBIDDEN: "Inventory yêu cầu tài khoản ADMIN đã xác thực MFA." } });
    }
  }

  return (
    <form onSubmit={submit} onChange={edited} className="space-y-8">
      <PageHeader title="Tạo kho" description="Kho là master data của Inventory. Trạng thái kho chỉ ảnh hưởng phân bổ mới, không tự di chuyển hoặc giải phóng tồn." />
      {validation ? <Alert variant="danger">{validation}</Alert> : null}
      {submitError.message ? <ErrorState error={create.error} message={inventoryErrorMessage(create.error) ?? submitError.message} /> : null}
      {create.isUnresolved ? <CommandRecovery commandId={create.commandId} /> : null}

      <fieldset disabled={create.inputsLocked} className="space-y-8">
      <FormSection title="Thông tin kho">
        <FieldBlock id="warehouse-name" label="Tên kho *">
          <Input id="warehouse-name" required maxLength={120} value={name} onChange={(event) => setName(event.target.value)} />
        </FieldBlock>
      </FormSection>

      <FormSection title="Địa chỉ kho" description="Nhập địa chỉ vận hành chính xác theo chứng từ được phê duyệt.">
        <AddressFields values={{ line1, line2, locality, region, postalCode, countryCode }} setters={{ setLine1, setLine2, setLocality, setRegion, setPostalCode, setCountryCode }} />
      </FormSection>

      <FormSection title="Điều kiện phân bổ" description="Kho hoạt động, không outage và có số lượng bán được mới đủ điều kiện nhận phân bổ.">
        <div className="flex items-start justify-between gap-6 rounded-lg border border-border bg-surface-2 p-4">
          <div className="space-y-1"><Label htmlFor="warehouse-active">Cho phép hoạt động</Label><p className="text-caption text-muted-foreground">Tắt để loại kho khỏi các lần phân bổ mới.</p></div>
          <Switch id="warehouse-active" checked={isActive} onCheckedChange={(checked) => { edited(); setIsActive(checked); }} />
        </div>
        <div className="grid gap-5 sm:grid-cols-2">
          <FieldBlock id="warehouse-state" label="Trạng thái vận hành *">
            <NativeSelect id="warehouse-state" value={operationalState} onChange={(event) => setOperationalState(event.target.value as "NORMAL" | "OUTAGE")}>
              <option value="NORMAL">Bình thường</option><option value="OUTAGE">Tạm ngưng</option>
            </NativeSelect>
          </FieldBlock>
          <FieldBlock id="warehouse-priority" label="Ưu tiên phân bổ *" description="Số nhỏ hơn được xét trước; khi bằng nhau Inventory dùng mã kho.">
            <Input id="warehouse-priority" type="number" min={0} step={1} required value={priority} onChange={(event) => setPriority(event.target.value)} />
          </FieldBlock>
        </div>
      </FormSection>

      <FormSection title="Lý do và bằng chứng" description="Thông tin này được lưu trong audit bất biến.">
        <FieldBlock id="warehouse-reason" label="Lý do tạo kho *"><Textarea id="warehouse-reason" required maxLength={160} value={reason} onChange={(event) => setReason(event.target.value)} /></FieldBlock>
        <FieldBlock id="warehouse-evidence" label="Tham chiếu bằng chứng *"><Input id="warehouse-evidence" required maxLength={512} value={evidence} onChange={(event) => setEvidence(event.target.value)} /></FieldBlock>
      </FormSection>

      </fieldset>
      <div className="flex justify-end"><SubmitButton pending={create.isPending} retry={create.isUnresolved} retryAfterSeconds={create.retryDelaySeconds}>Tạo kho</SubmitButton></div>
    </form>
  );
}

export function AddressFields({
  values,
  setters,
}: {
  values: { line1: string; line2: string; locality: string; region: string; postalCode: string; countryCode: string };
  setters: {
    setLine1: (value: string) => void; setLine2: (value: string) => void; setLocality: (value: string) => void;
    setRegion: (value: string) => void; setPostalCode: (value: string) => void; setCountryCode: (value: string) => void;
  };
}) {
  return (
    <>
      <FieldBlock id="warehouse-line1" label="Địa chỉ dòng 1 *"><Input id="warehouse-line1" required maxLength={200} value={values.line1} onChange={(event) => setters.setLine1(event.target.value)} /></FieldBlock>
      <FieldBlock id="warehouse-line2" label="Địa chỉ dòng 2"><Input id="warehouse-line2" maxLength={200} value={values.line2} onChange={(event) => setters.setLine2(event.target.value)} /></FieldBlock>
      <div className="grid gap-5 sm:grid-cols-2">
        <FieldBlock id="warehouse-locality" label="Tỉnh/thành phố *"><Input id="warehouse-locality" required maxLength={120} value={values.locality} onChange={(event) => setters.setLocality(event.target.value)} /></FieldBlock>
        <FieldBlock id="warehouse-region" label="Khu vực"><Input id="warehouse-region" maxLength={120} value={values.region} onChange={(event) => setters.setRegion(event.target.value)} /></FieldBlock>
        <FieldBlock id="warehouse-postal" label="Mã bưu chính"><Input id="warehouse-postal" maxLength={32} value={values.postalCode} onChange={(event) => setters.setPostalCode(event.target.value)} /></FieldBlock>
        <FieldBlock id="warehouse-country" label="Mã quốc gia *" description="ISO 3166-1 alpha-2, ví dụ VN."><Input id="warehouse-country" required minLength={2} maxLength={2} pattern="[A-Z]{2}" value={values.countryCode} onChange={(event) => setters.setCountryCode(event.target.value.toUpperCase())} /></FieldBlock>
      </div>
    </>
  );
}
