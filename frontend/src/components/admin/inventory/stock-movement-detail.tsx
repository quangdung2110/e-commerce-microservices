"use client";

import { useState } from "react";
import { useQuery } from "@tanstack/react-query";

import { FormSection } from "@/components/admin/form-section";
import { IdChip } from "@/components/admin/id-chip";
import {
  DetailSkeleton,
  FieldBlock,
  MutationResult,
  ResultItem,
  SubmitButton,
} from "@/components/admin/inventory/inventory-shared";
import { PageHeader } from "@/components/admin/page-header";
import { Alert } from "@/components/ui/alert";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { ErrorState } from "@/components/ui/error-state";
import { Input } from "@/components/ui/input";
import { NativeSelect } from "@/components/ui/native-select";
import { Textarea } from "@/components/ui/textarea";
import { useCommand } from "@/hooks/use-command";
import { useSubmitError } from "@/hooks/use-submit-error";
import { inventoryAdminApi, type MovementReversalRequest } from "@/lib/api/inventory-admin";
import { formatDateTime } from "@/lib/format";
import {
  formatSignedQuantity,
  inventoryErrorMessage,
  REVERSAL_REASON_LABELS,
  type ReversalReason,
} from "@/lib/inventory";

export function StockMovementDetail({ movementId }: { movementId: string }) {
  const query = useQuery({
    queryKey: ["inventory", "movement", movementId],
    queryFn: ({ signal }) => inventoryAdminApi.getStockMovement(movementId, signal),
  });

  if (query.isPending) return <DetailSkeleton />;
  if (query.isError) return <ErrorState error={query.error} message={inventoryErrorMessage(query.error)} onRetry={() => void query.refetch()} />;

  return <MovementContent movement={query.data} />;
}

function MovementContent({ movement }: { movement: Awaited<ReturnType<typeof inventoryAdminApi.getStockMovement>> }) {
  const submitError = useSubmitError();
  const [validation, setValidation] = useState<string | null>(null);
  const [result, setResult] = useState<Awaited<ReturnType<typeof inventoryAdminApi.reverseStockMovement>> | null>(null);
  const [sequence, setSequence] = useState("1");
  const [quantity, setQuantity] = useState("");
  const [reason, setReason] = useState<ReversalReason>("WRONG_QUANTITY");
  const [reasonNote, setReasonNote] = useState("");
  const [caseReference, setCaseReference] = useState("");
  const [evidenceReference, setEvidenceReference] = useState("");

  const reverse = useCommand({
    mutationFn: (input: MovementReversalRequest, context) =>
      inventoryAdminApi.reverseStockMovement(movement.movement_id, input, context.commandId),
    onSuccess: setResult,
  });

  function edited() {
    if (reverse.isError) reverse.startNewIntent();
    submitError.reset();
    setValidation(null);
    setResult(null);
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    submitError.reset();
    setValidation(null);
    const reversalSequence = Number(sequence);
    const parsedQuantity = Number(quantity);
    if (!Number.isInteger(reversalSequence) || reversalSequence < 1 || !Number.isInteger(parsedQuantity) || parsedQuantity < 1) {
      setValidation("Số thứ tự reversal và số lượng phải là số nguyên dương.");
      return;
    }
    if (!reasonNote.trim() || !caseReference.trim() || !evidenceReference.trim()) {
      setValidation("Vui lòng nhập ghi chú lý do, hồ sơ sửa sai và bằng chứng.");
      return;
    }
    try {
      await reverse.mutateAsync({
        reversal_sequence: reversalSequence,
        quantity: parsedQuantity,
        reason,
        reason_note: reasonNote.trim(),
        correction_case_reference: caseReference.trim(),
        evidence_reference: evidenceReference.trim(),
      });
    } catch (error) {
      submitError.capture(error, {
        overrides: { FORBIDDEN: "Inventory yêu cầu tài khoản ADMIN đã xác thực MFA." },
      });
    }
  }

  const deltas = [
    ["on_hand", "Tồn thực tế"],
    ["available_to_sell", "Có thể bán"],
    ["reserved", "Đã giữ"],
    ["committed", "Đã cam kết"],
    ["quarantine", "Cách ly"],
  ] as const;

  return (
    <div className="space-y-8">
      <PageHeader title="Movement tồn kho" description="Movement là bằng chứng bất biến. Sửa sai tạo movement bù trừ mới thay vì thay đổi bản ghi cũ." />

      <Card>
        <CardHeader className="border-b border-border"><CardTitle>Thông tin movement</CardTitle></CardHeader>
        <CardContent className="grid gap-6 pt-6 sm:grid-cols-2 lg:grid-cols-3">
          <ResultItem label="Movement"><IdChip value={movement.movement_id} /></ResultItem>
          <ResultItem label="Kho"><IdChip value={movement.warehouse_id} /></ResultItem>
          <ResultItem label="SKU"><IdChip value={movement.sku_id} /></ResultItem>
          <ResultItem label="Loại nguồn"><span className="font-mono">{movement.source_operation_type}</span></ResultItem>
          <ResultItem label="Thao tác nguồn"><IdChip value={movement.source_operation_id} /></ResultItem>
          <ResultItem label="Ghi nhận lúc"><span>{formatDateTime(movement.recorded_at)}</span></ResultItem>
          {movement.reverses_movement_id ? <ResultItem label="Bù trừ movement"><IdChip value={movement.reverses_movement_id} /></ResultItem> : null}
        </CardContent>
      </Card>

      <section aria-labelledby="movement-deltas" className="space-y-5">
        <h2 id="movement-deltas" className="text-headline-5 font-serif font-normal">Thay đổi bucket</h2>
        <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-5">
          {deltas.map(([key, label]) => (
            <div key={key} className="rounded-xl border border-border bg-surface-overlay p-6">
              <p className="text-caption text-muted-foreground">{label}</p>
              <p className="mt-2 font-mono text-headline-5 tabular-nums">{formatSignedQuantity(movement.deltas[key])}</p>
            </div>
          ))}
        </div>
      </section>

      <form onSubmit={submit} className="space-y-6" onChange={edited}>
        <FormSection title="Tạo reversal" description="Chỉ receipt hoặc adjustment bán được đủ điều kiện mới có thể được bù trừ.">
          {validation ? <Alert variant="danger">{validation}</Alert> : null}
          {submitError.message ? <ErrorState error={reverse.error} message={inventoryErrorMessage(reverse.error) ?? submitError.message} /> : null}
          {result ? <MutationResult result={result} /> : null}
          <div className="grid gap-5 sm:grid-cols-2">
            <FieldBlock id="reversal-sequence" label="Số thứ tự reversal *" description="Bắt đầu từ 1 và tăng dần cho cùng movement.">
              <Input id="reversal-sequence" type="number" min={1} step={1} required value={sequence} onChange={(event) => setSequence(event.target.value)} />
            </FieldBlock>
            <FieldBlock id="reversal-quantity" label="Số lượng *">
              <Input id="reversal-quantity" type="number" min={1} step={1} required value={quantity} onChange={(event) => setQuantity(event.target.value)} />
            </FieldBlock>
          </div>
          <FieldBlock id="reversal-reason" label="Lý do *">
            <NativeSelect id="reversal-reason" value={reason} onChange={(event) => setReason(event.target.value as ReversalReason)}>
              {(Object.keys(REVERSAL_REASON_LABELS) as ReversalReason[]).map((value) => <option key={value} value={value}>{REVERSAL_REASON_LABELS[value]}</option>)}
            </NativeSelect>
          </FieldBlock>
          <FieldBlock id="reversal-note" label="Ghi chú lý do *">
            <Textarea id="reversal-note" required maxLength={1000} value={reasonNote} onChange={(event) => setReasonNote(event.target.value)} />
          </FieldBlock>
          <div className="grid gap-5 sm:grid-cols-2">
            <FieldBlock id="reversal-case" label="Mã hồ sơ sửa sai *">
              <Input id="reversal-case" required maxLength={128} value={caseReference} onChange={(event) => setCaseReference(event.target.value)} />
            </FieldBlock>
            <FieldBlock id="reversal-evidence" label="Tham chiếu bằng chứng *">
              <Input id="reversal-evidence" required maxLength={512} value={evidenceReference} onChange={(event) => setEvidenceReference(event.target.value)} />
            </FieldBlock>
          </div>
        </FormSection>
        <div className="flex justify-end"><SubmitButton pending={reverse.isPending} retry={reverse.isError} retryAfterSeconds={reverse.retryDelaySeconds}>Tạo reversal</SubmitButton></div>
      </form>
    </div>
  );
}
