import { describe, expect, it } from "vitest";

import { internalPathOf } from "@/lib/notification-link";

const ORIGIN = "http://localhost:3000";

describe("internalPathOf", () => {
  it("đổi URL tuyệt đối cùng origin thành đường dẫn kèm query và hash", () => {
    expect(internalPathOf("http://localhost:3000/reviews/0f6d3a8e", ORIGIN)).toBe("/reviews/0f6d3a8e");
    expect(internalPathOf("http://localhost:3000/orders/10234?review=1#top", ORIGIN)).toBe("/orders/10234?review=1#top");
  });

  it("giữ nguyên đường dẫn tương đối một gạch chéo", () => {
    expect(internalPathOf("/orders/1000234", ORIGIN)).toBe("/orders/1000234");
  });

  it("không bao giờ theo link tới origin khác", () => {
    expect(internalPathOf("https://evil.example.com/orders/1", ORIGIN)).toBeNull();
    expect(internalPathOf("http://localhost:3001/orders/1", ORIGIN)).toBeNull();
    expect(internalPathOf("https://localhost:3000/orders/1", ORIGIN)).toBeNull();
  });

  it("từ chối URL cùng giao thức tới host khác và dạng lách bằng gạch chéo ngược", () => {
    expect(internalPathOf("//evil.example.com/path", ORIGIN)).toBeNull();
    expect(internalPathOf("/\\evil.example.com", ORIGIN)).toBeNull();
  });

  it("từ chối scheme không phải http(s) và chuỗi không phải URL", () => {
    expect(internalPathOf("javascript:alert(1)", ORIGIN)).toBeNull();
    expect(internalPathOf("data:text/html,<b>x</b>", ORIGIN)).toBeNull();
    expect(internalPathOf("không phải url", ORIGIN)).toBeNull();
  });

  it("trả null khi không có link", () => {
    expect(internalPathOf(null, ORIGIN)).toBeNull();
    expect(internalPathOf(undefined, ORIGIN)).toBeNull();
    expect(internalPathOf("   ", ORIGIN)).toBeNull();
  });
});
