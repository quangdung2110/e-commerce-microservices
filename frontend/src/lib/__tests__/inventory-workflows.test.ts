import { describe, expect, it } from "vitest";
import {
  canReceiveReturn,
  remainingReturnQuantity,
  inspectionPayload,
  dispositionPayload,
} from "@/lib/inventory-workflows";
import type { AdminRefundScope } from "@/lib/api/inventory-admin";
import { isActiveReindex } from "@/lib/labels/system";

describe("return workflow boundaries", () => {
  it("only accepts receipt before inspection completion", () => {
    for (const state of [
      "UNKNOWN",
      "INSPECTION_COMPLETED",
      "CANCELLED",
      "CLOSED",
      "CLOSED_INCOMPLETE",
    ] as const)
      expect(canReceiveReturn({ state } as AdminRefundScope)).toBe(false);
    expect(canReceiveReturn({ state: "REGISTERED" } as AdminRefundScope)).toBe(
      true,
    );
    expect(canReceiveReturn({ state: "IN_PROGRESS" } as AdminRefundScope)).toBe(
      true,
    );
    expect(
      remainingReturnQuantity({
        expected_quantity: 5,
        received_quantity: 3,
      } as AdminRefundScope["lines"][number]),
    ).toBe(2);
    expect(
      remainingReturnQuantity({
        expected_quantity: 5,
        received_quantity: 5,
      } as AdminRefundScope["lines"][number]),
    ).toBe(0);
  });
  it("PASS omits fail_reason; FAIL requires the selected reason", () => {
    const base = {
      inspection_id: "i",
      return_receipt_id: "r",
      return_receipt_line_id: "l",
      warehouse_id: "w",
      quantity: 1,
      checklist_reference: "c",
      evidence_reference: "e",
    };
    expect(inspectionPayload(base, "PASS", "DAMAGED")).not.toHaveProperty(
      "fail_reason",
    );
    expect(inspectionPayload(base, "FAIL", "MISSING_COMPONENTS")).toMatchObject(
      { result: "FAIL", fail_reason: "MISSING_COMPONENTS" },
    );
  });
  it("HOLD omits physical departure; supplier return locks its reason", () => {
    const base = {
      disposition_id: "d",
      disposition_line_id: "l",
      inspection_id: "i",
      warehouse_id: "w",
      sku_id: "s",
      quantity: 1,
      evidence_reference: "e",
    };
    const hold = dispositionPayload(
      base,
      "HOLD",
      "SAFETY_REVIEW",
      "old",
      "old-time",
    );
    expect(hold).not.toHaveProperty("physical_departure_reference");
    expect(hold).not.toHaveProperty("departed_at");
    expect(
      dispositionPayload(
        base,
        "RETURN_TO_SUPPLIER",
        "UNSAFE",
        "departure",
        "time",
      ),
    ).toMatchObject({
      reason: "SUPPLIER_RETURN_AUTHORIZED",
      physical_departure_reference: "departure",
      departed_at: "time",
    });
  });
  it("stops reindex polling for every terminal status", () => {
    for (const status of [
      "COMPLETED",
      "FAILED",
      "ROLLED_BACK",
      "SUPERSEDED",
      "CANCELLED",
    ] as const)
      expect(isActiveReindex(status)).toBe(false);
    for (const status of [
      "STARTED",
      "BUILDING",
      "CATCHING_UP",
      "CUTOVER",
    ] as const)
      expect(isActiveReindex(status)).toBe(true);
  });
});
