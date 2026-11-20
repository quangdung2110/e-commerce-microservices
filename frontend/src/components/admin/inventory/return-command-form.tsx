"use client";

import { useEffect, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { DateTimeInput } from "@/components/admin/date-time-input";
import {
  FieldBlock,
  MutationResult,
  SubmitButton,
} from "@/components/admin/inventory/inventory-shared";
import { Alert } from "@/components/ui/alert";
import { ErrorState } from "@/components/ui/error-state";
import { Input } from "@/components/ui/input";
import { NativeSelect } from "@/components/ui/native-select";
import { Textarea } from "@/components/ui/textarea";
import { useCommand } from "@/hooks/use-command";
import {
  inventoryAdminApi,
  type StockMutationResponse,
  type InspectionRequest,
  type QuarantineDispositionRequest,
} from "@/lib/api/inventory-admin";
import { inventoryErrorMessage } from "@/lib/inventory";
import {
  inspectionPayload,
  dispositionPayload,
} from "@/lib/inventory-workflows";
import {
  FAIL_REASON_LABELS,
  DISPOSITION_REASON_LABELS,
} from "@/lib/labels/inventory";
import { isRetryable } from "@/lib/api/problem";
import { isCommandOutcomeUnknown } from "@/lib/admin-command";

export type ReturnAction =
  | {
      kind: "receipt";
      refund_scope_id: string;
      order_line_id: string;
      actual_sku_id: string;
      max: number;
    }
  | {
      kind: "inspection";
      warehouse_id: string;
      return_receipt_id: string;
      return_receipt_line_id: string;
      max: number;
    }
  | {
      kind: "disposition";
      warehouse_id: string;
      sku_id: string;
      inspection_id: string;
      max: number;
    }
  | { kind: "reversal" };

export function ReturnCommandForm({
  action,
  onBusyChange,
}: {
  action: ReturnAction;
  onBusyChange?: (busy: boolean) => void;
}) {
  const cache = useQueryClient();
  const [values, setValues] = useState<Record<string, string>>({
    quantity: "1",
    reversal_sequence: "1",
    time: new Date().toISOString(),
  });
  const [inspectionResult, setInspectionResult] = useState<"PASS" | "FAIL">(
    "PASS",
  );
  const [failReason, setFailReason] =
    useState<Extract<InspectionRequest, { result: "FAIL" }>["fail_reason"]>(
      "DAMAGED",
    );
  const [disposition, setDisposition] =
    useState<QuarantineDispositionRequest["disposition"]>("HOLD");
  const [reason, setReason] = useState("AWAITING_DISPOSITION");
  const [reversalReason, setReversalReason] = useState<
    "WRONG_RECORD" | "DISPOSAL_ABORTED" | "SUPPLIER_RETURNED"
  >("WRONG_RECORD");
  const [commandId, setCommandId] = useState<string>();
  const [validation, setValidation] = useState<string | null>(null);
  const command = useCommand<StockMutationResponse, Record<string, string>>({
    retry: false,
    mutationFn: async (input, { commandId: id }) => {
      setCommandId(id);
      const quantity = Number(input.quantity);
      const evidence_reference = input.evidence_reference.trim();
      if (action.kind === "receipt")
        return inventoryAdminApi.recordReturnReceipt(
          {
            refund_scope_id: action.refund_scope_id,
            order_line_id: action.order_line_id,
            actual_sku_id: action.actual_sku_id,
            warehouse_id: input.warehouse_id.trim(),
            quantity,
            evidence_reference,
            return_receipt_id: input.return_receipt_id.trim(),
            return_receipt_line_id: input.return_receipt_line_id.trim(),
            package_reference: input.package_reference.trim(),
            received_at: input.time,
          },
          id,
        );
      if (action.kind === "inspection")
        return inventoryAdminApi.recordInspection(
          inspectionPayload(
            {
              warehouse_id: action.warehouse_id,
              return_receipt_id: action.return_receipt_id,
              return_receipt_line_id: action.return_receipt_line_id,
              inspection_id: input.inspection_id.trim(),
              quantity,
              checklist_reference: input.checklist_reference.trim(),
              evidence_reference,
            },
            inspectionResult,
            failReason,
          ),
          id,
        );
      if (action.kind === "disposition")
        return inventoryAdminApi.recordQuarantineDisposition(
          dispositionPayload(
            {
              warehouse_id: action.warehouse_id,
              sku_id: action.sku_id,
              inspection_id: action.inspection_id,
              disposition_id: input.disposition_id.trim(),
              disposition_line_id: input.disposition_line_id.trim(),
              quantity,
              evidence_reference,
            },
            disposition,
            reason,
            input.physical_departure_reference?.trim() ?? "",
            input.time,
          ),
          id,
        );
      return inventoryAdminApi.reverseQuarantineDisposition(
        input.disposition_id.trim(),
        {
          disposition_line_id: input.disposition_line_id.trim(),
          reversal_sequence: Number(input.reversal_sequence),
          quantity,
          reason: reversalReason,
          reason_note: input.reason_note.trim(),
          physically_returned_at: input.time,
          physical_return_reference: input.physical_return_reference.trim(),
          evidence_reference,
        },
        id,
      );
    },
    onSuccess: async () => {
      await cache.invalidateQueries({ queryKey: ["inventory"] });
    },
  });
  const uncertain =
    command.isError &&
    (isRetryable(command.error) || isCommandOutcomeUnknown(command.error));
  const locked = command.isPending || uncertain || command.isSuccess;
  useEffect(() => {
    onBusyChange?.(command.isPending);
  }, [command.isPending, onBusyChange]);
  const options =
    disposition === "HOLD"
      ? [
          "AWAITING_DISPOSITION",
          "SUPPLIER_CLAIM",
          "SAFETY_REVIEW",
          "EVIDENCE_PRESERVATION",
        ]
      : disposition === "DISPOSE"
        ? ["UNSAFE", "UNREPAIRABLE", "EXPIRED", "POLICY_REQUIRED"]
        : ["SUPPLIER_RETURN_AUTHORIZED"];
  function input(
    name: string,
    label: string,
    maxLength = 128,
    multiline = false,
  ) {
    const props = {
      id: name,
      required: true,
      maxLength,
      disabled: locked,
      value: values[name] ?? "",
      onChange: (
        event: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>,
      ) => setValues({ ...values, [name]: event.target.value }),
    };
    return (
      <FieldBlock id={name} label={`${label} *`}>
        {multiline ? <Textarea {...props} /> : <Input {...props} />}
      </FieldBlock>
    );
  }
  return (
    <form
      className="space-y-6"
      onSubmit={(event) => {
        event.preventDefault();
        if (command.isPending || command.isSuccess || command.isRetryDelayed)
          return;
        if (!uncertain) {
          const quantity = Number(values.quantity);
          if (
            !Number.isSafeInteger(quantity) ||
            quantity < 1 ||
            (action.kind !== "reversal" && quantity > action.max)
          ) {
            setValidation(
              "Nhập số lượng nguyên dương trong số lượng còn có thể xử lý.",
            );
            return;
          }
          if (
            action.kind === "reversal" &&
            (!Number.isSafeInteger(Number(values.reversal_sequence)) ||
              Number(values.reversal_sequence) < 1)
          ) {
            setValidation(
              "Số thứ tự đảo ngược phải là số nguyên dương liên tiếp.",
            );
            return;
          }
          const required =
            action.kind === "receipt"
              ? [
                  "warehouse_id",
                  "return_receipt_id",
                  "return_receipt_line_id",
                  "package_reference",
                  "time",
                ]
              : action.kind === "inspection"
                ? ["inspection_id", "checklist_reference"]
                : action.kind === "disposition"
                  ? [
                      "disposition_id",
                      "disposition_line_id",
                      ...(disposition === "HOLD"
                        ? []
                        : ["physical_departure_reference", "time"]),
                    ]
                  : [
                      "disposition_id",
                      "disposition_line_id",
                      "reason_note",
                      "physical_return_reference",
                      "time",
                    ];
          if (
            [...required, "evidence_reference"].some(
              (field) => !values[field]?.trim(),
            )
          ) {
            setValidation(
              "Điền đầy đủ các trường bắt buộc; nội dung không được chỉ có khoảng trắng.",
            );
            return;
          }
        }
        setValidation(null);
        if (!command.isPending && !command.isSuccess)
          command.mutate(
            uncertain && command.variables ? command.variables : values,
          );
      }}
    >
      <Alert variant="info" title="Ghi nhận theo hàng thực tế">
        {action.kind === "inspection"
          ? "PASS chuyển hàng về tồn bán được. FAIL là kết quả cuối, hàng tiếp tục cách ly."
          : action.kind === "disposition"
            ? "HOLD chỉ ghi chú giữ hàng. Huỷ hoặc trả nhà cung cấp cần bằng chứng hàng đã rời kho."
            : action.kind === "reversal"
              ? "Chỉ ghi nhận khi hàng đã thực tế quay về đúng kho. Hàng được phục hồi vào cách ly."
              : "Hàng nhận vào cách ly; SKU và dòng đơn được cố định theo yêu cầu trả hàng."}
      </Alert>
      {command.isError ? (
        <ErrorState
          error={command.error}
          message={inventoryErrorMessage(command.error)}
        />
      ) : null}
      {validation ? (
        <Alert variant="danger" title="Kiểm tra dữ liệu">
          {validation}
        </Alert>
      ) : null}
      {uncertain ? (
        <Alert variant="warning" title="Kết quả lệnh chưa rõ">
          Giữ nguyên dữ liệu để thử lại đúng lệnh. Mã lệnh:{" "}
          <span className="break-all font-mono">{commandId}</span>. Có thể tra
          mã này tại mục Kết quả lệnh.
        </Alert>
      ) : null}
      {command.data ? <MutationResult result={command.data} /> : null}
      <fieldset disabled={locked} className="space-y-5">
        {action.kind === "receipt" ? (
          <>
            {input("warehouse_id", "Kho thực tế nhận hàng")}
            {input("return_receipt_id", "Mã phiếu nhận hàng trả")}
            {input("return_receipt_line_id", "Mã dòng phiếu")}
            {input("package_reference", "Mã kiện hàng")}
          </>
        ) : null}
        {action.kind === "inspection" ? (
          <>
            {input("inspection_id", "Mã kiểm định")}
            {input("checklist_reference", "Tham chiếu bảng kiểm")}
            <FieldBlock id="inspection-result" label="Kết quả cuối *">
              <NativeSelect
                id="inspection-result"
                value={inspectionResult}
                onChange={(event) =>
                  setInspectionResult(event.target.value as "PASS" | "FAIL")
                }
              >
                <option value="PASS">Đạt — chuyển về tồn bán được</option>
                <option value="FAIL">Không đạt — giữ cách ly</option>
              </NativeSelect>
            </FieldBlock>
            {inspectionResult === "FAIL" ? (
              <FieldBlock id="fail-reason" label="Lý do không đạt *">
                <NativeSelect
                  id="fail-reason"
                  value={failReason}
                  onChange={(event) =>
                    setFailReason(event.target.value as typeof failReason)
                  }
                >
                  {Object.entries(FAIL_REASON_LABELS).map(([value, label]) => (
                    <option key={value} value={value}>
                      {label}
                    </option>
                  ))}
                </NativeSelect>
              </FieldBlock>
            ) : null}
          </>
        ) : null}
        {action.kind === "disposition" || action.kind === "reversal" ? (
          <>
            {input("disposition_id", "Mã phiếu xử lý")}
            {input("disposition_line_id", "Mã dòng xử lý")}
          </>
        ) : null}
        {action.kind === "disposition" ? (
          <>
            <FieldBlock id="disposition" label="Cách xử lý *">
              <NativeSelect
                id="disposition"
                value={disposition}
                onChange={(event) => {
                  const value = event.target.value as typeof disposition;
                  setDisposition(value);
                  setReason(
                    value === "HOLD"
                      ? "AWAITING_DISPOSITION"
                      : value === "DISPOSE"
                        ? "UNSAFE"
                        : "SUPPLIER_RETURN_AUTHORIZED",
                  );
                }}
              >
                <option value="HOLD">Tiếp tục giữ hàng</option>
                <option value="DISPOSE">Huỷ hàng</option>
                <option value="RETURN_TO_SUPPLIER">Trả nhà cung cấp</option>
              </NativeSelect>
            </FieldBlock>
            <FieldBlock id="disposition-reason" label="Lý do *">
              <NativeSelect
                id="disposition-reason"
                value={reason}
                onChange={(event) => setReason(event.target.value)}
              >
                {options.map((value) => (
                  <option key={value} value={value}>
                    {
                      DISPOSITION_REASON_LABELS[
                        value as keyof typeof DISPOSITION_REASON_LABELS
                      ]
                    }
                  </option>
                ))}
              </NativeSelect>
            </FieldBlock>
            {disposition !== "HOLD"
              ? input("physical_departure_reference", "Tham chiếu hàng rời kho")
              : null}
          </>
        ) : null}
        {action.kind === "reversal" ? (
          <>
            <FieldBlock
              id="reversal_sequence"
              label="Số thứ tự đảo ngược liên tiếp *"
            >
              <Input
                id="reversal_sequence"
                required
                type="number"
                min={1}
                step={1}
                value={values.reversal_sequence}
                onChange={(event) =>
                  setValues({
                    ...values,
                    reversal_sequence: event.target.value,
                  })
                }
              />
            </FieldBlock>
            <FieldBlock id="reversal-reason" label="Lý do *">
              <NativeSelect
                id="reversal-reason"
                value={reversalReason}
                onChange={(event) =>
                  setReversalReason(event.target.value as typeof reversalReason)
                }
              >
                <option value="WRONG_RECORD">Ghi nhận sai</option>
                <option value="DISPOSAL_ABORTED">Huỷ bỏ việc tiêu huỷ</option>
                <option value="SUPPLIER_RETURNED">
                  Nhà cung cấp trả lại hàng
                </option>
              </NativeSelect>
            </FieldBlock>
            {input("reason_note", "Giải thích", 1000, true)}
            {input("physical_return_reference", "Tham chiếu hàng quay về kho")}
          </>
        ) : null}
        <FieldBlock id="quantity" label="Số lượng *">
          <Input
            id="quantity"
            type="number"
            min={1}
            max={action.kind === "reversal" ? undefined : action.max}
            step={1}
            required
            value={values.quantity}
            onChange={(event) =>
              setValues({ ...values, quantity: event.target.value })
            }
          />
        </FieldBlock>
        {action.kind === "receipt" ||
        action.kind === "reversal" ||
        (action.kind === "disposition" && disposition !== "HOLD") ? (
          <FieldBlock id="time" label="Thời điểm thực tế *">
            <DateTimeInput
              id="time"
              required
              value={values.time}
              onValueChange={(time) =>
                setValues({ ...values, time: time ?? "" })
              }
            />
          </FieldBlock>
        ) : null}
        {input("evidence_reference", "Tham chiếu bằng chứng", 512)}
        <label className="flex items-start gap-3 text-[14px]">
          <input
            type="checkbox"
            required
            className="mt-1 size-4 accent-primary"
          />
          Tôi đã đối chiếu số lượng và bằng chứng; xác nhận ghi nhận thao tác
          này.
        </label>
      </fieldset>
      {!command.isSuccess ? (
        <SubmitButton
          pending={command.isPending}
          retry={uncertain}
          retryAfterSeconds={command.retryDelaySeconds}
        >
          Xác nhận ghi nhận
        </SubmitButton>
      ) : null}
    </form>
  );
}
