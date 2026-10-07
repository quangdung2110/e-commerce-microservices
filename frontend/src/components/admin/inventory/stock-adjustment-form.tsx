"use client";

import { useState } from "react";

import { FormSection } from "@/components/admin/form-section";
import { FieldBlock, MutationResult, SubmitButton } from "@/components/admin/inventory/inventory-shared";
import { PageHeader } from "@/components/admin/page-header";
import { Alert } from "@/components/ui/alert";
import { ErrorState } from "@/components/ui/error-state";
import { Input } from "@/components/ui/input";
import { NativeSelect } from "@/components/ui/native-select";
import { Textarea } from "@/components/ui/textarea";
import { useCommand } from "@/hooks/use-command";
import { useSubmitError } from "@/hooks/use-submit-error";
import { inventoryAdminApi, type StockAdjustmentRequest, type StockMutationResponse } from "@/lib/api/inventory-admin";
import {
  ADJUSTMENT_REASON_LABELS,
  directionForReason,
  inventoryErrorMessage,
  type AdjustmentDirection,
  type AdjustmentReason,
} from "@/lib/inventory";

export function StockAdjustmentForm() {
  const submitError = useSubmitError();
  const [validation, setValidation] = useState<string | null>(null);
  const [result, setResult] = useState<StockMutationResponse | null>(null);
  const [warehouseId, setWarehouseId] = useState("");
  const [skuId, setSkuId] = useState("");
  const [direction, setDirection] = useState<AdjustmentDirection>("DECREASE");
  const [quantity, setQuantity] = useState("");
  const [caseId, setCaseId] = useState("");
  const [lineId, setLineId] = useState("");
  const [reason, setReason] = useState<AdjustmentReason>("COUNT_LOSS");
  const [reasonNote, setReasonNote] = useState("");
  const [evidence, setEvidence] = useState("");

  const adjustment = useCommand({
    mutationFn: (input: StockAdjustmentRequest, context) =>
      inventoryAdminApi.recordStockAdjustment(input, context.commandId),
    onSuccess: setResult,
  });

  const effectiveDirection = directionForReason(reason, direction);

  function edited() {
    if (adjustment.isError) adjustment.startNewIntent();
    submitError.reset();
    setValidation(null);
    setResult(null);
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    submitError.reset();
    setValidation(null);
    const parsedQuantity = Number(quantity);
    if (!Number.isInteger(parsedQuantity) || parsedQuantity < 1) {
      setValidation("Số lượng điều chỉnh phải là số nguyên dương.");
      return;
    }
    if (![warehouseId, skuId, caseId, lineId, evidence].every((value) => value.trim())) {
      setValidation("Vui lòng nhập đầy đủ mã kho, SKU, hồ sơ điều chỉnh, dòng hồ sơ và bằng chứng.");
      return;
    }
    if (reason === "DATA_CORRECTION" && !reasonNote.trim()) {
      setValidation("Sửa dữ liệu bắt buộc có ghi chú giải thích.");
      return;
    }
    try {
      await adjustment.mutateAsync({
        warehouse_id: warehouseId.trim(),
        sku_id: skuId.trim(),
        direction: effectiveDirection,
        quantity: parsedQuantity,
        adjustment_case_id: caseId.trim(),
        adjustment_line_id: lineId.trim(),
        reason,
        ...(reasonNote.trim() ? { reason_note: reasonNote.trim() } : {}),
        evidence_reference: evidence.trim(),
      });
    } catch (error) {
      submitError.capture(error, { overrides: { FORBIDDEN: "Inventory yêu cầu tài khoản ADMIN đã xác thực MFA." } });
    }
  }

  return (
    <form onSubmit={submit} onChange={edited} className="space-y-8">
      <PageHeader title="Điều chỉnh tồn" description="Điều chỉnh âm chỉ lấy từ tồn có thể bán; không được lấy từ hàng đã giữ, đã cam kết hoặc cách ly." />
      <Alert variant="warning" title="Đây không phải công cụ sửa cân đối hệ thống">Nếu bucket hoặc movement ledger không khớp, hãy mở incident thay vì dùng adjustment để che sai lệch.</Alert>
      {validation ? <Alert variant="danger">{validation}</Alert> : null}
      {submitError.message ? <ErrorState error={adjustment.error} message={inventoryErrorMessage(adjustment.error) ?? submitError.message} /> : null}
      {result ? <MutationResult result={result} /> : null}

      <FormSection title="Vị trí và số lượng">
        <div className="grid gap-5 sm:grid-cols-2">
          <FieldBlock id="adjustment-warehouse" label="Mã kho *"><Input id="adjustment-warehouse" required maxLength={128} value={warehouseId} onChange={(event) => setWarehouseId(event.target.value)} /></FieldBlock>
          <FieldBlock id="adjustment-sku" label="Mã SKU *"><Input id="adjustment-sku" required maxLength={128} value={skuId} onChange={(event) => setSkuId(event.target.value)} /></FieldBlock>
          <FieldBlock id="adjustment-quantity" label="Số lượng *"><Input id="adjustment-quantity" type="number" required min={1} step={1} value={quantity} onChange={(event) => setQuantity(event.target.value)} /></FieldBlock>
          <FieldBlock id="adjustment-direction" label="Hướng điều chỉnh *" description={reason === "DATA_CORRECTION" ? "Sửa dữ liệu cho phép chọn hướng." : "Hướng được cố định theo lý do đã chọn."}>
            <NativeSelect id="adjustment-direction" value={effectiveDirection} disabled={reason !== "DATA_CORRECTION"} onChange={(event) => setDirection(event.target.value as AdjustmentDirection)}><option value="INCREASE">Tăng</option><option value="DECREASE">Giảm</option></NativeSelect>
          </FieldBlock>
        </div>
      </FormSection>

      <FormSection title="Lý do và hồ sơ">
        <FieldBlock id="adjustment-reason" label="Lý do *"><NativeSelect id="adjustment-reason" value={reason} onChange={(event) => setReason(event.target.value as AdjustmentReason)}>{(Object.keys(ADJUSTMENT_REASON_LABELS) as AdjustmentReason[]).map((value) => <option key={value} value={value}>{ADJUSTMENT_REASON_LABELS[value]}</option>)}</NativeSelect></FieldBlock>
        {reason === "DATA_CORRECTION" ? <FieldBlock id="adjustment-note" label="Ghi chú sửa dữ liệu *"><Textarea id="adjustment-note" required maxLength={1000} value={reasonNote} onChange={(event) => setReasonNote(event.target.value)} /></FieldBlock> : null}
        <div className="grid gap-5 sm:grid-cols-2">
          <FieldBlock id="adjustment-case" label="Mã hồ sơ điều chỉnh *"><Input id="adjustment-case" required maxLength={128} value={caseId} onChange={(event) => setCaseId(event.target.value)} /></FieldBlock>
          <FieldBlock id="adjustment-line" label="Mã dòng hồ sơ *"><Input id="adjustment-line" required maxLength={128} value={lineId} onChange={(event) => setLineId(event.target.value)} /></FieldBlock>
        </div>
        <FieldBlock id="adjustment-evidence" label="Tham chiếu bằng chứng *"><Input id="adjustment-evidence" required maxLength={512} value={evidence} onChange={(event) => setEvidence(event.target.value)} /></FieldBlock>
      </FormSection>

      <div className="flex justify-end"><SubmitButton pending={adjustment.isPending} retry={adjustment.isError} retryAfterSeconds={adjustment.retryDelaySeconds}>Ghi nhận điều chỉnh</SubmitButton></div>
    </form>
  );
}
