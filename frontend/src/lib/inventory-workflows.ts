import type {
  AdminRefundScope,
  InspectionRequest,
  QuarantineDispositionRequest,
} from "@/lib/api/inventory-admin";

export function canReceiveReturn(scope: AdminRefundScope) {
  return scope.state === "REGISTERED" || scope.state === "IN_PROGRESS";
}
export function remainingReturnQuantity(
  line: AdminRefundScope["lines"][number],
) {
  return Math.max(0, line.expected_quantity - line.received_quantity);
}
export function inspectionPayload(
  base: Omit<InspectionRequest, "result" | "fail_reason">,
  result: "PASS" | "FAIL",
  failReason: Extract<InspectionRequest, { result: "FAIL" }>["fail_reason"],
): InspectionRequest {
  return result === "PASS"
    ? { ...base, result }
    : { ...base, result, fail_reason: failReason };
}
export function dispositionPayload(
  base: Omit<
    QuarantineDispositionRequest,
    "disposition" | "reason" | "physical_departure_reference" | "departed_at"
  >,
  disposition: QuarantineDispositionRequest["disposition"],
  reason: string,
  departure: string,
  departedAt: string,
): QuarantineDispositionRequest {
  if (disposition === "HOLD")
    return {
      ...base,
      disposition,
      reason: reason as Extract<
        QuarantineDispositionRequest,
        { disposition: "HOLD" }
      >["reason"],
    };
  if (disposition === "RETURN_TO_SUPPLIER")
    return {
      ...base,
      disposition,
      reason: "SUPPLIER_RETURN_AUTHORIZED",
      physical_departure_reference: departure,
      departed_at: departedAt,
    };
  return {
    ...base,
    disposition,
    reason: reason as Extract<
      QuarantineDispositionRequest,
      { disposition: "DISPOSE" }
    >["reason"],
    physical_departure_reference: departure,
    departed_at: departedAt,
  };
}
