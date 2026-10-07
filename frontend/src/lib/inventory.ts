import type {
  StockAdjustmentRequest,
  StockReceiptRequest,
} from "@/lib/api/inventory-admin";
import { problemStatus } from "@/lib/api/problem";

export type ReceiptReason = StockReceiptRequest["reason"];
export type AdjustmentReason = StockAdjustmentRequest["reason"];
export type AdjustmentDirection = StockAdjustmentRequest["direction"];

export const OPERATIONAL_STATE_LABELS = {
  UNKNOWN: "Chưa xác định",
  NORMAL: "Bình thường",
  OUTAGE: "Tạm ngưng",
} as const;

export const RECEIPT_REASON_LABELS: Record<ReceiptReason, string> = {
  INITIAL_STOCK_LOAD: "Nạp tồn ban đầu",
  SUPPLIER_RECEIPT: "Nhận hàng từ nhà cung cấp",
};

export const ADJUSTMENT_REASON_LABELS: Record<AdjustmentReason, string> = {
  COUNT_GAIN: "Kiểm đếm thừa",
  COUNT_LOSS: "Kiểm đếm thiếu",
  DAMAGE_REMOVAL: "Loại bỏ hàng hỏng",
  EXPIRY_REMOVAL: "Loại bỏ hàng hết hạn",
  DATA_CORRECTION: "Sửa dữ liệu",
};

export const REVERSAL_REASON_LABELS = {
  DUPLICATE_ENTRY: "Ghi nhận trùng",
  WRONG_QUANTITY: "Sai số lượng",
  WRONG_SKU: "Sai SKU",
  WRONG_WAREHOUSE: "Sai kho",
  SOURCE_DOCUMENT_VOIDED: "Chứng từ nguồn bị huỷ",
} as const;

export type ReversalReason = keyof typeof REVERSAL_REASON_LABELS;

export function directionForReason(
  reason: AdjustmentReason,
  selected: AdjustmentDirection
): AdjustmentDirection {
  if (reason === "COUNT_GAIN") return "INCREASE";
  if (reason === "DATA_CORRECTION") return selected;
  return "DECREASE";
}

export function inventoryErrorMessage(error: unknown): string | undefined {
  if (problemStatus(error) === 403) {
    return "Inventory yêu cầu tài khoản ADMIN đã xác thực MFA. Hãy hoàn tất MFA rồi thử lại.";
  }
  return undefined;
}

export function formatSignedQuantity(value: number): string {
  if (value === 0) return "0";
  return value > 0 ? `+${value.toLocaleString("vi-VN")}` : value.toLocaleString("vi-VN");
}
