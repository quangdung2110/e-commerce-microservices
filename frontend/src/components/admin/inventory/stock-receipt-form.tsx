"use client";

import { useState } from "react";

import { FormSection } from "@/components/admin/form-section";
import { FieldBlock, MutationResult, SubmitButton } from "@/components/admin/inventory/inventory-shared";
import { PageHeader } from "@/components/admin/page-header";
import { Alert } from "@/components/ui/alert";
import { ErrorState } from "@/components/ui/error-state";
import { Input } from "@/components/ui/input";
import { NativeSelect } from "@/components/ui/native-select";
import { useCommand } from "@/hooks/use-command";
import { useSubmitError } from "@/hooks/use-submit-error";
import { inventoryAdminApi, type StockMutationResponse, type StockReceiptRequest } from "@/lib/api/inventory-admin";
import { inventoryErrorMessage, RECEIPT_REASON_LABELS, type ReceiptReason } from "@/lib/inventory";

export function StockReceiptForm() {
  const submitError = useSubmitError();
  const [validation, setValidation] = useState<string | null>(null);
  const [result, setResult] = useState<StockMutationResponse | null>(null);
  const [warehouseId, setWarehouseId] = useState("");
  const [skuId, setSkuId] = useState("");
  const [quantity, setQuantity] = useState("");
  const [receiptId, setReceiptId] = useState("");
  const [receiptLineId, setReceiptLineId] = useState("");
  const [reason, setReason] = useState<ReceiptReason>("SUPPLIER_RECEIPT");
  const [sourceReference, setSourceReference] = useState("");
  const [evidence, setEvidence] = useState("");

  const receipt = useCommand({
    mutationFn: (input: StockReceiptRequest, context) =>
      inventoryAdminApi.recordStockReceipt(input, context.commandId),
    onSuccess: setResult,
  });

  function edited() {
    if (receipt.isError) receipt.startNewIntent();
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
      setValidation("Số lượng nhập phải là số nguyên dương.");
      return;
    }
    if (![warehouseId, skuId, receiptId, receiptLineId, sourceReference, evidence].every((value) => value.trim())) {
      setValidation("Vui lòng nhập đầy đủ mã kho, SKU, định danh phiếu, chứng từ nguồn và bằng chứng.");
      return;
    }

    const common = {
      warehouse_id: warehouseId.trim(),
      sku_id: skuId.trim(),
      quantity: parsedQuantity,
      receipt_id: receiptId.trim(),
      receipt_line_id: receiptLineId.trim(),
      evidence_reference: evidence.trim(),
    };
    const input: StockReceiptRequest = reason === "INITIAL_STOCK_LOAD"
      ? { ...common, reason, baseline_batch_id: sourceReference.trim() }
      : { ...common, reason, goods_receipt_reference: sourceReference.trim() };

    try {
      await receipt.mutateAsync(input);
    } catch (error) {
      submitError.capture(error, { overrides: { FORBIDDEN: "Inventory yêu cầu tài khoản ADMIN đã xác thực MFA." } });
    }
  }

  return (
    <form onSubmit={submit} onChange={edited} className="space-y-8">
      <PageHeader title="Nhập hàng" description="Ghi nhận hàng bán được vào một kho. Mỗi dòng phiếu là một operation identity và không được tạo hiệu ứng hai lần." />
      {validation ? <Alert variant="danger">{validation}</Alert> : null}
      {submitError.message ? <ErrorState error={receipt.error} message={inventoryErrorMessage(receipt.error) ?? submitError.message} /> : null}
      {result ? <MutationResult result={result} /> : null}

      <FormSection title="Vị trí nhận hàng">
        <div className="grid gap-5 sm:grid-cols-2">
          <FieldBlock id="receipt-warehouse" label="Mã kho *"><Input id="receipt-warehouse" required maxLength={128} value={warehouseId} onChange={(event) => setWarehouseId(event.target.value)} /></FieldBlock>
          <FieldBlock id="receipt-sku" label="Mã SKU *"><Input id="receipt-sku" required maxLength={128} value={skuId} onChange={(event) => setSkuId(event.target.value)} /></FieldBlock>
          <FieldBlock id="receipt-quantity" label="Số lượng *"><Input id="receipt-quantity" type="number" required min={1} step={1} value={quantity} onChange={(event) => setQuantity(event.target.value)} /></FieldBlock>
          <FieldBlock id="receipt-reason" label="Loại nhập hàng *"><NativeSelect id="receipt-reason" value={reason} onChange={(event) => { setReason(event.target.value as ReceiptReason); setSourceReference(""); }}><option value="SUPPLIER_RECEIPT">{RECEIPT_REASON_LABELS.SUPPLIER_RECEIPT}</option><option value="INITIAL_STOCK_LOAD">{RECEIPT_REASON_LABELS.INITIAL_STOCK_LOAD}</option></NativeSelect></FieldBlock>
        </div>
      </FormSection>

      <FormSection title="Định danh phiếu" description="Receipt ID và line ID là business identity của thao tác vật lý.">
        <div className="grid gap-5 sm:grid-cols-2">
          <FieldBlock id="receipt-id" label="Mã phiếu nhận *"><Input id="receipt-id" required maxLength={128} value={receiptId} onChange={(event) => setReceiptId(event.target.value)} /></FieldBlock>
          <FieldBlock id="receipt-line-id" label="Mã dòng phiếu *"><Input id="receipt-line-id" required maxLength={128} value={receiptLineId} onChange={(event) => setReceiptLineId(event.target.value)} /></FieldBlock>
        </div>
        <FieldBlock id="receipt-source" label={reason === "INITIAL_STOCK_LOAD" ? "Mã batch baseline đã duyệt *" : "Mã GRN / phiếu giao hàng *"}>
          <Input id="receipt-source" required maxLength={128} value={sourceReference} onChange={(event) => setSourceReference(event.target.value)} />
        </FieldBlock>
        <FieldBlock id="receipt-evidence" label="Tham chiếu bằng chứng *"><Input id="receipt-evidence" required maxLength={512} value={evidence} onChange={(event) => setEvidence(event.target.value)} /></FieldBlock>
      </FormSection>

      <div className="flex justify-end"><SubmitButton pending={receipt.isPending} retry={receipt.isError} retryAfterSeconds={receipt.retryDelaySeconds}>Ghi nhận nhập hàng</SubmitButton></div>
    </form>
  );
}
