import type { components } from "@/lib/api/generated/promotion";
import type { EnumLabel } from "@/lib/labels/types";

type Schemas = components["schemas"];

export const CAMPAIGN_PUBLICATION_LABELS: Record<Schemas["CampaignPublicationStatus"], EnumLabel> = {
  UNKNOWN: { text: "Không xác định", tone: "default" },
  DRAFT: { text: "Bản nháp", tone: "default" },
  PUBLISHED: { text: "Đã xuất bản", tone: "success" },
  PAUSED: { text: "Tạm dừng", tone: "warning" },
  CANCELLED: { text: "Đã huỷ", tone: "destructive" },
};

/** Phase được suy ra theo đồng hồ của backend, không phải trạng thái lưu trữ. */
export const CAMPAIGN_PHASE_LABELS: Record<Schemas["CampaignPhase"], EnumLabel> = {
  UNKNOWN: { text: "Không xác định", tone: "default" },
  DRAFT: { text: "Chưa xuất bản", tone: "default" },
  UPCOMING: { text: "Sắp diễn ra", tone: "info" },
  ACTIVE: { text: "Đang diễn ra", tone: "success" },
  ENDED: { text: "Đã kết thúc", tone: "default" },
  PAUSED: { text: "Tạm dừng", tone: "warning" },
  CANCELLED: { text: "Đã huỷ", tone: "destructive" },
};

export const DISTRIBUTION_MODE_LABELS: Record<Schemas["DistributionMode"], EnumLabel> = {
  UNKNOWN: { text: "Không xác định", tone: "default" },
  PUBLIC_SAVE: { text: "Lưu công khai", tone: "info" },
  LIMITED_CLAIM: { text: "Số lượng giới hạn", tone: "clay" },
  DIRECT_GRANT: { text: "Cấp trực tiếp", tone: "default" },
};

export const DISTRIBUTION_MODE_HINTS: Record<Exclude<Schemas["DistributionMode"], "UNKNOWN">, string> = {
  PUBLIC_SAVE: "Người mua tự lưu voucher vào ví khi campaign đang diễn ra.",
  LIMITED_CLAIM: "Chỉ một số suất cố định; ai nhận trước được trước.",
  DIRECT_GRANT: "Chỉ quản trị viên cấp voucher cho từng người dùng.",
};

export const WALLET_STATUS_LABELS: Record<Schemas["WalletStatus"], EnumLabel> = {
  UNKNOWN: { text: "Không xác định", tone: "default" },
  SAVED: { text: "Đã lưu trong ví", tone: "success" },
  USED: { text: "Đã dùng", tone: "default" },
  EXPIRED: { text: "Hết hạn", tone: "warning" },
};

export type TransitionTarget = Schemas["CampaignTransitionRequest"]["target_status"];

/** Cách đo hạn dùng của voucher đã lưu. */
export const VALIDITY_MODE_LABELS: Record<Schemas["ValidityPolicy"]["mode"], string> = {
  CAMPAIGN_END: "Đến khi campaign kết thúc",
  DURATION_AFTER_SAVE: "Trong một khoảng thời gian kể từ lúc lưu",
  FIXED_DATE: "Đến một thời điểm cố định",
};
