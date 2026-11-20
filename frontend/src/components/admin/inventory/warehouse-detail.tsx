"use client";

import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";

import { FormSection } from "@/components/admin/form-section";
import { CommandRecovery, DetailSkeleton, FieldBlock, SubmitButton } from "@/components/admin/inventory/inventory-shared";
import { AddressFields } from "@/components/admin/inventory/warehouse-create-form";
import { IdChip } from "@/components/admin/id-chip";
import { PageHeader } from "@/components/admin/page-header";
import { Alert } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { ErrorState } from "@/components/ui/error-state";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { NativeSelect } from "@/components/ui/native-select";
import { Switch } from "@/components/ui/switch";
import { Textarea } from "@/components/ui/textarea";
import { useToast } from "@/components/ui/toast";
import { useInventoryCommand } from "@/hooks/use-inventory-command";
import { useSubmitError } from "@/hooks/use-submit-error";
import { inventoryAdminApi, type UpdateWarehouseRequest, type Warehouse } from "@/lib/api/inventory-admin";
import { inventoryErrorMessage, OPERATIONAL_STATE_LABELS } from "@/lib/inventory";
import { problemCode } from "@/lib/api/problem";

export function WarehouseDetail({ warehouseId }: { warehouseId: string }) {
  const [formVersion, setFormVersion] = useState(0);
  const query = useQuery({
    queryKey: ["inventory", "warehouse", warehouseId],
    queryFn: ({ signal }) => inventoryAdminApi.getWarehouse(warehouseId, signal),
  });

  if (query.isPending) return <DetailSkeleton />;
  if (query.isError) return <ErrorState error={query.error} message={inventoryErrorMessage(query.error)} onRetry={() => void query.refetch()} />;
  return <WarehouseEditForm key={`${warehouseId}:${formVersion}`} warehouse={query.data} reloading={query.isFetching} onReload={async () => {
    const fresh = await query.refetch();
    if (!fresh.isError) setFormVersion((version) => version + 1);
  }} />;
}

function WarehouseEditForm({ warehouse, onReload, reloading }: { warehouse: Warehouse; onReload: () => Promise<void>; reloading: boolean }) {
  const queryClient = useQueryClient();
  const { addToast } = useToast();
  const submitError = useSubmitError();
  const [validation, setValidation] = useState<string | null>(null);
  const [name, setName] = useState(warehouse.name);
  const [line1, setLine1] = useState(warehouse.address.line1);
  const [line2, setLine2] = useState(warehouse.address.line2 ?? "");
  const [locality, setLocality] = useState(warehouse.address.locality);
  const [region, setRegion] = useState(warehouse.address.region ?? "");
  const [postalCode, setPostalCode] = useState(warehouse.address.postal_code ?? "");
  const [countryCode, setCountryCode] = useState(warehouse.address.country_code);
  const [isActive, setIsActive] = useState(warehouse.is_active);
  const [operationalState, setOperationalState] = useState<Warehouse["operational_state"]>(warehouse.operational_state);
  const [priority, setPriority] = useState(String(warehouse.fulfillment_priority));
  const [reason, setReason] = useState("");
  const [evidence, setEvidence] = useState("");

  const update = useInventoryCommand({
    mutationFn: (input: UpdateWarehouseRequest, context) =>
      inventoryAdminApi.updateWarehouse(warehouse.warehouse_id, input, context.commandId),
    onSuccess: (response) => {
      queryClient.setQueryData(["inventory", "warehouse", warehouse.warehouse_id], response.warehouse);
      setReason("");
      setEvidence("");
      addToast({ type: "success", title: response.replayed ? "Đã khôi phục kết quả cập nhật kho" : "Đã cập nhật kho" });
    },
  });
  const revisionConflict = update.isError && problemCode(update.error) === "WAREHOUSE_CONFLICT";

  function edited() {
    if (revisionConflict || !update.startNewIntent()) return;
    submitError.reset();
    setValidation(null);
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (update.isPending || update.isRetryDelayed || revisionConflict || reloading) return;
    submitError.reset();
    setValidation(null);
    const fulfillmentPriority = Number(priority);
    if (operationalState === "UNKNOWN") {
      setValidation("Hãy chọn trạng thái Bình thường hoặc Tạm ngưng trước khi lưu.");
      return;
    }
    if (!name.trim() || !line1.trim() || !locality.trim() || !reason.trim() || !evidence.trim()) {
      setValidation("Vui lòng nhập tên kho, địa chỉ bắt buộc, lý do thay đổi và bằng chứng.");
      return;
    }
    if (!/^[A-Z]{2}$/.test(countryCode) || !Number.isInteger(fulfillmentPriority) || fulfillmentPriority < 0) {
      setValidation("Kiểm tra lại mã quốc gia và ưu tiên phân bổ.");
      return;
    }
    try {
      await update.mutateAsync({
        expected_revision: warehouse.revision,
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
      <PageHeader title="Chi tiết kho" description="Chỉnh sửa master data không tự giải phóng, chuyển kho hoặc viết lại các cam kết đang có." />
      <div className="flex flex-wrap items-center gap-3 rounded-xl border border-border bg-surface-2 p-4">
        <IdChip value={warehouse.warehouse_id} />
        <Badge variant={isActive ? "success" : "default"}>{isActive ? "Đang hoạt động" : "Đã tắt"}</Badge>
        <Badge variant={operationalState === "NORMAL" ? "info" : operationalState === "OUTAGE" ? "warning" : "default"}>{OPERATIONAL_STATE_LABELS[operationalState]}</Badge>
        <span className="text-caption text-muted-foreground">Revision {warehouse.revision}</span>
      </div>
      {validation ? <Alert variant="danger">{validation}</Alert> : null}
      {submitError.message ? <ErrorState error={update.error} message={inventoryErrorMessage(update.error) ?? submitError.message} /> : null}
      {update.isUnresolved ? <CommandRecovery commandId={update.commandId} /> : null}
      {revisionConflict ? (
        <Button type="button" variant="outline" disabled={reloading} onClick={() => void onReload()}>
          {reloading ? "Đang tải bản mới" : "Tải bản kho mới nhất"}
        </Button>
      ) : null}

      <fieldset disabled={update.inputsLocked || revisionConflict || reloading} className="space-y-8">
      <FormSection title="Thông tin kho">
        <FieldBlock id="warehouse-name" label="Tên kho *">
          <Input id="warehouse-name" required maxLength={120} value={name} onChange={(event) => setName(event.target.value)} />
        </FieldBlock>
      </FormSection>

      <FormSection title="Địa chỉ kho"><AddressFields values={{ line1, line2, locality, region, postalCode, countryCode }} setters={{ setLine1, setLine2, setLocality, setRegion, setPostalCode, setCountryCode }} /></FormSection>
      <FormSection title="Điều kiện phân bổ">
        <div className="flex items-start justify-between gap-6 rounded-lg border border-border bg-surface-2 p-4">
          <div className="space-y-1"><Label htmlFor="warehouse-active">Cho phép hoạt động</Label><p className="text-caption text-muted-foreground">Thay đổi chỉ áp dụng cho phân bổ mới.</p></div>
          <Switch id="warehouse-active" checked={isActive} onCheckedChange={(checked) => { edited(); setIsActive(checked); }} />
        </div>
        <div className="grid gap-5 sm:grid-cols-2">
          <FieldBlock id="warehouse-state" label="Trạng thái vận hành *"><NativeSelect id="warehouse-state" value={operationalState} onChange={(event) => setOperationalState(event.target.value as Warehouse["operational_state"])}><option value="UNKNOWN" disabled>Chưa xác định</option><option value="NORMAL">Bình thường</option><option value="OUTAGE">Tạm ngưng</option></NativeSelect></FieldBlock>
          <FieldBlock id="warehouse-priority" label="Ưu tiên phân bổ *"><Input id="warehouse-priority" type="number" min={0} step={1} required value={priority} onChange={(event) => setPriority(event.target.value)} /></FieldBlock>
        </div>
      </FormSection>
      <FormSection title="Audit thay đổi" description="Mỗi lần lưu cần lý do và bằng chứng mới.">
        <FieldBlock id="warehouse-reason" label="Lý do thay đổi *"><Textarea id="warehouse-reason" required maxLength={160} value={reason} onChange={(event) => setReason(event.target.value)} /></FieldBlock>
        <FieldBlock id="warehouse-evidence" label="Tham chiếu bằng chứng *"><Input id="warehouse-evidence" required maxLength={512} value={evidence} onChange={(event) => setEvidence(event.target.value)} /></FieldBlock>
      </FormSection>
      </fieldset>
      <div className="flex justify-end"><SubmitButton pending={update.isPending} retry={update.isUnresolved} disabled={revisionConflict || reloading} retryAfterSeconds={update.retryDelaySeconds}>Lưu thay đổi</SubmitButton></div>
    </form>
  );
}
