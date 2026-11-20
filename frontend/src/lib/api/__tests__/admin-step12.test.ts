import { beforeEach, describe, expect, it, vi } from "vitest";
vi.mock("@/lib/api/client", () => ({
  api: { get: vi.fn(), post: vi.fn(), put: vi.fn() },
}));
import { api } from "@/lib/api/client";
import { inventoryAdminApi } from "@/lib/api/inventory-admin";
import { adminReviewApi } from "@/lib/api/admin-review";
import { adminSystemApi } from "@/lib/api/admin-system";
beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(api.get).mockResolvedValue({ data: {} });
  vi.mocked(api.post).mockResolvedValue({ data: {} });
  vi.mocked(api.put).mockResolvedValue({ data: {} });
});
describe("step 12 public admin boundaries", () => {
  it("uses warehouse-scoped queues with cursor and cancellation", async () => {
    const signal = new AbortController().signal;
    for (const [method, path] of [
      [inventoryAdminApi.listDispatchQueue, "dispatch-queue"],
      [inventoryAdminApi.listInspectionQueue, "inspection-queue"],
      [inventoryAdminApi.listQuarantineItems, "quarantine-items"],
    ] as const) {
      await method({ warehouse_id: "wh", cursor: "opaque", limit: 25 }, signal);
      expect(api.get).toHaveBeenLastCalledWith(
        `/api/v1/admin/inventory/${path}`,
        { params: { warehouse_id: "wh", cursor: "opaque", limit: 25 }, signal },
      );
    }
    await inventoryAdminApi.findRefundScopeByOrder("order/1", signal);
    expect(api.get).toHaveBeenLastCalledWith(
      "/api/v1/admin/inventory/refund-scopes",
      { params: { order_id: "order/1" }, signal },
    );
  });
  it("carries Inventory command identity on return operations", async () => {
    const body = {
      refund_scope_id: "r",
      order_line_id: "l",
      actual_sku_id: "s",
      warehouse_id: "w",
      quantity: 1,
      return_receipt_id: "receipt",
      return_receipt_line_id: "line",
      package_reference: "p",
      received_at: "2026-10-10T00:00:00Z",
      evidence_reference: "e",
    };
    await inventoryAdminApi.recordReturnReceipt(body, "command");
    expect(api.post).toHaveBeenLastCalledWith(
      "/api/v1/admin/inventory/return-receipts",
      body,
      {
        headers: {
          "Idempotency-Key": "command",
          "X-Correlation-ID": "command",
          "X-Causation-ID": "command",
        },
      },
    );
    const reversal = {
      disposition_line_id: "line",
      reversal_sequence: 1,
      quantity: 1,
      reason: "WRONG_RECORD" as const,
      reason_note: "wrong",
      physically_returned_at: "time",
      physical_return_reference: "return",
      evidence_reference: "e",
    };
    await inventoryAdminApi.reverseQuarantineDisposition(
      "disposition/1",
      reversal,
      "command",
    );
    expect(api.post).toHaveBeenLastCalledWith(
      "/api/v1/admin/inventory/quarantine-dispositions/disposition%2F1/reversals",
      reversal,
      expect.any(Object),
    );
  });
  it("does not invent idempotency, actor or reason body for Review dismissal", async () => {
    await adminReviewApi.dismiss("review/1");
    expect(api.post).toHaveBeenLastCalledWith(
      "/api/v1/admin/reviews/review%2F1/report-dismissals",
    );
    await adminReviewApi.reply("r", "reply");
    expect(api.put).toHaveBeenCalledWith("/api/v1/admin/reviews/r/reply", {
      content: "reply",
    });
    await adminReviewApi.takedown("r", {
      legal_case_id: "legal",
      reason: "reason",
    });
    expect(api.post).toHaveBeenLastCalledWith(
      "/api/v1/admin/reviews/r/takedown",
      { legal_case_id: "legal", reason: "reason" },
    );
  });
  it("uses Search v2 and Auth deletion has no request body", async () => {
    await adminSystemApi.createRun("reason");
    expect(api.post).toHaveBeenLastCalledWith("/api/v2/search/reindex-runs", {
      reason: "reason",
    });
    await adminSystemApi.cancelRun("run/1");
    expect(api.post).toHaveBeenLastCalledWith(
      "/api/v2/search/reindex-runs/run%2F1/cancellation",
    );
    await adminSystemApi.deleteAccount("account/1");
    expect(api.post).toHaveBeenLastCalledWith(
      "/api/v1/auth/admin/accounts/account%2F1/deletion-requests",
    );
  });
});
