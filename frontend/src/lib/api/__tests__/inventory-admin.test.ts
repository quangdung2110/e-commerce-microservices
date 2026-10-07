import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@/lib/api/client", () => ({
  api: {
    get: vi.fn(),
    post: vi.fn(),
    patch: vi.fn(),
  },
}));

import { api } from "@/lib/api/client";
import { inventoryAdminApi } from "@/lib/api/inventory-admin";

const commandId = "0199cafe-0000-7000-8000-000000000001";

describe("inventoryAdminApi", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(api.get).mockResolvedValue({ data: {} });
    vi.mocked(api.post).mockResolvedValue({ data: {} });
    vi.mocked(api.patch).mockResolvedValue({ data: {} });
  });

  it("gửi đủ ba header định danh cho lệnh tạo kho", async () => {
    const input = {
      name: "Kho miền Nam",
      address: { line1: "1 Nguyễn Huệ", locality: "TP.HCM", country_code: "VN" },
      is_active: true,
      operational_state: "NORMAL" as const,
      fulfillment_priority: 10,
      reason: "Mở kho miền Nam",
      evidence_reference: "approval/WH-001",
    };

    await inventoryAdminApi.createWarehouse(input, commandId);

    expect(api.post).toHaveBeenCalledWith(
      "/api/v1/admin/inventory/warehouses",
      input,
      {
        headers: {
          "Idempotency-Key": commandId,
          "X-Correlation-ID": commandId,
          "X-Causation-ID": commandId,
        },
      }
    );
  });

  it("dùng PATCH và encode mã kho", async () => {
    const input = {
      expected_revision: 3,
      is_active: false,
      reason: "Tạm đóng kho",
      evidence_reference: "incident/12",
    };

    await inventoryAdminApi.updateWarehouse("kho/miền nam", input, commandId);

    expect(api.patch).toHaveBeenCalledWith(
      "/api/v1/admin/inventory/warehouses/kho%2Fmi%E1%BB%81n%20nam",
      input,
      expect.objectContaining({ headers: expect.objectContaining({ "Idempotency-Key": commandId }) })
    );
  });

  it("gọi đúng endpoint nhập hàng", async () => {
    const input = {
      warehouse_id: "wh-1",
      sku_id: "sku-1",
      quantity: 5,
      receipt_id: "receipt-1",
      receipt_line_id: "line-1",
      reason: "SUPPLIER_RECEIPT" as const,
      goods_receipt_reference: "GRN-1",
      evidence_reference: "evidence/1",
    };

    await inventoryAdminApi.recordStockReceipt(input, commandId);

    expect(api.post).toHaveBeenCalledWith(
      "/api/v1/admin/inventory/stock-receipts",
      input,
      expect.any(Object)
    );
  });

  it("gọi đúng endpoint điều chỉnh tồn", async () => {
    const input = {
      warehouse_id: "wh-1",
      sku_id: "sku-1",
      direction: "DECREASE" as const,
      quantity: 2,
      adjustment_case_id: "case-1",
      adjustment_line_id: "line-1",
      reason: "COUNT_LOSS" as const,
      evidence_reference: "count/1",
    };

    await inventoryAdminApi.recordStockAdjustment(input, commandId);

    expect(api.post).toHaveBeenCalledWith(
      "/api/v1/admin/inventory/stock-adjustments",
      input,
      expect.any(Object)
    );
  });

  it("encode cả mã kho và SKU khi tra tồn", async () => {
    const controller = new AbortController();
    await inventoryAdminApi.getStockPosition("wh/1", "sku đỏ", controller.signal);

    expect(api.get).toHaveBeenCalledWith(
      "/api/v1/admin/inventory/stock-positions/wh%2F1/sku%20%C4%91%E1%BB%8F",
      { signal: controller.signal }
    );
  });

  it("gọi endpoint reversal theo movement", async () => {
    const input = {
      reversal_sequence: 1,
      quantity: 2,
      reason: "WRONG_QUANTITY" as const,
      reason_note: "Nhập dư hai sản phẩm",
      correction_case_reference: "case-2",
      evidence_reference: "evidence/2",
    };

    await inventoryAdminApi.reverseStockMovement("move/1", input, commandId);

    expect(api.post).toHaveBeenCalledWith(
      "/api/v1/admin/inventory/stock-movements/move%2F1/reversals",
      input,
      expect.any(Object)
    );
  });

  it("đọc phân bổ và ghi nhận xuất kho nguyên dòng", async () => {
    const signal = new AbortController().signal;
    await inventoryAdminApi.getReservation("order/1", signal);
    expect(api.get).toHaveBeenCalledWith(
      "/api/v1/admin/inventory/reservations/order%2F1",
      { signal }
    );

    const input = {
      dispatch_id: "dispatch-1",
      dispatch_line_id: "dispatch-line-1",
      order_id: "123",
      order_line_id: "456",
      warehouse_id: "wh-1",
      sku_id: "sku-1",
      quantity: 2,
      handed_over_at: "2026-10-06T03:00:00.000Z",
      handover_reference: "carrier/TRK-1",
      evidence_reference: "evidence/DSP-1",
    };
    await inventoryAdminApi.recordPhysicalDispatch(input, commandId);
    expect(api.post).toHaveBeenCalledWith(
      "/api/v1/admin/inventory/physical-dispatches",
      input,
      expect.objectContaining({
        headers: {
          "Idempotency-Key": commandId,
          "X-Correlation-ID": commandId,
          "X-Causation-ID": commandId,
        },
      })
    );
  });
});
