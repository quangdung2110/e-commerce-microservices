import type { components } from "@/lib/api/generated/product";
import type { EnumLabel } from "@/lib/labels/types";

type Schemas = components["schemas"];

export const PRODUCT_STATUS_LABELS: Record<Schemas["ProductStatus"], EnumLabel> = {
  DRAFT: { text: "Nháp", tone: "default" },
  ACTIVE: { text: "Đang bán", tone: "success" },
  HIDDEN: { text: "Đang ẩn", tone: "warning" },
};

export const SKU_STATUS_LABELS: Record<Schemas["SkuStatus"], EnumLabel> = {
  ACTIVE: { text: "Đang bán", tone: "success" },
  INACTIVE: { text: "Ngừng bán", tone: "default" },
};

export const ATTRIBUTE_VALUE_TYPE_LABELS: Record<Schemas["AttributeValueType"], EnumLabel> = {
  TEXT: { text: "Chữ", tone: "default" },
  NUMBER: { text: "Số", tone: "default" },
  BOOLEAN: { text: "Có/Không", tone: "default" },
  SELECT: { text: "Danh sách chọn", tone: "default" },
};

export const MEDIA_TYPE_LABELS: Record<Schemas["MediaType"], EnumLabel> = {
  IMAGE: { text: "Ảnh", tone: "default" },
  VIDEO: { text: "Video", tone: "default" },
};
