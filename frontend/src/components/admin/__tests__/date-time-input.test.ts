import { describe, expect, it } from "vitest";

import {
  localInputToVietnamIso,
  vietnamIsoToLocalInput,
} from "@/components/admin/date-time-input";

describe("DateTimeInput", () => {
  it("hiển thị RFC 3339 theo giờ Việt Nam", () => {
    expect(vietnamIsoToLocalInput("2026-09-29T08:30:00Z")).toBe("2026-09-29T15:30");
  });

  it("gửi datetime-local thành RFC 3339 đúng mốc thời gian", () => {
    expect(localInputToVietnamIso("2026-09-29T15:30")).toBe("2026-09-29T08:30:00.000Z");
  });

  it("không gửi giá trị không hợp lệ", () => {
    expect(localInputToVietnamIso("29/09/2026 15:30")).toBeUndefined();
    expect(vietnamIsoToLocalInput("không-phải-ngày")).toBe("");
  });
});
