import type { EnumLabel } from "@/lib/labels/types";

export const REFUND_SCOPE_LABELS: Record<string, EnumLabel> = {
  UNKNOWN: { text: "Chưa xác định", tone: "default" },
  REGISTERED: { text: "Chờ nhận hàng", tone: "info" },
  IN_PROGRESS: { text: "Đang nhận và kiểm định", tone: "warning" },
  INSPECTION_COMPLETED: { text: "Đã kiểm định đủ", tone: "success" },
  CANCELLED: { text: "Đã huỷ", tone: "default" },
  CLOSED: { text: "Đã đóng", tone: "default" },
  CLOSED_INCOMPLETE: { text: "Đóng khi chưa đủ hàng", tone: "warning" },
};
export const FAIL_REASON_LABELS = {
  DAMAGED: "Hư hỏng",
  USED_OR_ALTERED: "Đã dùng hoặc thay đổi",
  MISSING_COMPONENTS: "Thiếu phụ kiện",
  SAFETY_OR_HYGIENE: "Không đạt an toàn / vệ sinh",
  COUNTERFEIT_OR_UNVERIFIABLE: "Giả hoặc không xác minh được",
  NOT_RESELLABLE_BY_POLICY: "Không được bán lại theo chính sách",
} as const;
export const DISPOSITION_REASON_LABELS = {
  AWAITING_DISPOSITION: "Chờ quyết định xử lý",
  SUPPLIER_CLAIM: "Chờ nhà cung cấp giải quyết",
  SAFETY_REVIEW: "Chờ xem xét an toàn",
  EVIDENCE_PRESERVATION: "Giữ bằng chứng",
  UNSAFE: "Không an toàn",
  UNREPAIRABLE: "Không sửa được",
  EXPIRED: "Hết hạn",
  POLICY_REQUIRED: "Theo chính sách",
  SUPPLIER_RETURN_AUTHORIZED: "Đã được phép trả nhà cung cấp",
} as const;

export const MOVEMENT_OPERATION_LABELS: Record<string, string> = {
  SELLABLE_STOCK_RECEIPT: "Nhập hàng bán được",
  STOCK_RECEIPT: "Nhập hàng",
  STOCK_ADJUSTMENT: "Điều chỉnh tồn",
  SELLABLE_STOCK_ADJUSTMENT: "Điều chỉnh tồn bán được",
  MOVEMENT_REVERSAL: "Đảo biến động",
  SELLABLE_STOCK_REVERSAL: "Đảo biến động tồn bán được",
  PHYSICAL_DISPATCH: "Xuất kho thực tế",
  RETURN_RECEIPT: "Nhận hàng trả",
  INSPECTION_PASS: "Kiểm định đạt",
  INSPECTION_FAIL: "Kiểm định không đạt",
  QUARANTINE_HOLD: "Giữ hàng cách ly",
  QUARANTINE_DISPOSITION: "Xử lý hàng cách ly",
  DISPOSITION_REVERSAL: "Đảo xử lý cách ly",
  RESERVATION: "Giữ hàng",
  RESERVATION_RELEASE: "Giải phóng giữ hàng",
  RESERVATION_COMMIT: "Cam kết phân bổ",
};
