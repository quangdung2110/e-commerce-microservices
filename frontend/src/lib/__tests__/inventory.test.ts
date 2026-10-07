import { describe, expect, it } from "vitest";

import { directionForReason, formatSignedQuantity, inventoryErrorMessage } from "@/lib/inventory";

describe("inventory domain helpers", () => {
  it("ép hướng tăng cho kiểm đếm thừa", () => {
    expect(directionForReason("COUNT_GAIN", "DECREASE")).toBe("INCREASE");
  });

  it("ép hướng giảm cho các lý do loại hàng", () => {
    expect(directionForReason("DAMAGE_REMOVAL", "INCREASE")).toBe("DECREASE");
    expect(directionForReason("EXPIRY_REMOVAL", "INCREASE")).toBe("DECREASE");
  });

  it("cho phép chọn hướng khi sửa dữ liệu", () => {
    expect(directionForReason("DATA_CORRECTION", "INCREASE")).toBe("INCREASE");
    expect(directionForReason("DATA_CORRECTION", "DECREASE")).toBe("DECREASE");
  });

  it("định dạng delta có dấu", () => {
    expect(formatSignedQuantity(12)).toBe("+12");
    expect(formatSignedQuantity(-3)).toBe("-3");
    expect(formatSignedQuantity(0)).toBe("0");
  });

  it("giải thích yêu cầu MFA khi Inventory trả 403", () => {
    expect(inventoryErrorMessage({ status: 403 })).toContain("MFA");
  });
});
