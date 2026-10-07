import { describe, expect, it } from "vitest";

import { formatDateTime, formatISODate, formatVND, toE164 } from "@/lib/format";
import { mediaUrl } from "@/lib/media";

/** Intl dùng khoảng trắng không ngắt; quy về khoảng trắng thường để so sánh. */
const plain = (value: string) => value.replace(/ | /g, " ");

describe("formatVND", () => {
  it("số nguyên VND, dấu chấm phân cách nghìn, không chia 100", () => {
    expect(plain(formatVND(1_234_567))).toBe("1.234.567 ₫");
    expect(plain(formatVND(0))).toBe("0 ₫");
  });
});

describe("formatDateTime", () => {
  it("quy về giờ Việt Nam bất kể múi giờ của máy", () => {
    // 17:30 UTC ngày 31/12 là 00:30 ngày 01/01 ở Việt Nam (UTC+7).
    expect(plain(formatDateTime("2025-12-31T17:30:00Z"))).toBe("00:30 01/01/2026");
  });

  it("chuỗi hỏng hiện gạch ngang thay vì Invalid Date", () => {
    expect(formatDateTime("not-a-date")).toBe("—");
  });
});

describe("formatISODate", () => {
  it("ngày lịch YYYY-MM-DD giữ nguyên, không bị lệch múi giờ", () => {
    expect(formatISODate("1990-05-01")).toBe("01/05/1990");
  });

  it("mốc RFC 3339 quy về ngày ở Việt Nam", () => {
    expect(formatISODate("2025-12-31T17:30:00Z")).toBe("01/01/2026");
  });
});

describe("toE164", () => {
  it("đổi số nội địa sang E.164", () => {
    expect(toE164("0912 345 678")).toBe("+84912345678");
    expect(toE164("+84912345678")).toBe("+84912345678");
  });
});

describe("mediaUrl", () => {
  it("percent-encode từng segment, giữ dấu /", () => {
    expect(mediaUrl("products/áo thun/1.jpg")).toBe("/media/products/%C3%A1o%20thun/1.jpg");
  });

  it("không có storage_key thì không có URL", () => {
    expect(mediaUrl(null)).toBeNull();
    expect(mediaUrl("")).toBeNull();
  });
});
