import type { ReindexStatus } from "@/lib/api/admin-system";
import type { EnumLabel } from "@/lib/labels/types";
export const REINDEX_LABELS: Record<ReindexStatus, EnumLabel> = {
  STARTED: { text: "Đã nhận", tone: "info" },
  BUILDING: { text: "Đang dựng chỉ mục", tone: "info" },
  CATCHING_UP: { text: "Đang đồng bộ thay đổi", tone: "info" },
  CUTOVER: { text: "Kiểm tra và chuyển chỉ mục", tone: "warning" },
  COMPLETED: { text: "Hoàn tất", tone: "success" },
  FAILED: { text: "Thất bại", tone: "destructive" },
  ROLLED_BACK: { text: "Đã về chỉ mục cũ", tone: "default" },
  SUPERSEDED: { text: "Được thay thế", tone: "default" },
  CANCELLED: { text: "Đã huỷ", tone: "default" },
};
export function isActiveReindex(status: ReindexStatus) {
  return (
    status === "STARTED" ||
    status === "BUILDING" ||
    status === "CATCHING_UP" ||
    status === "CUTOVER"
  );
}
