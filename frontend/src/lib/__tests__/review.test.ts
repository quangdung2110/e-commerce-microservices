import { describe, expect, it } from "vitest";

import { normalizeReviewContent, reviewExpiryNote } from "@/lib/review";
import { classifyReviewFile, REVIEW_MEDIA_RULES } from "@/lib/review-media";

const NOW = Date.parse("2026-10-06T00:00:00Z");
const MB = 1024 * 1024;

describe("reviewExpiryNote", () => {
  it("đếm ngày còn lại và báo gấp khi còn ít", () => {
    expect(reviewExpiryNote("2026-10-26T00:00:00Z", NOW)).toEqual({ text: "Còn 20 ngày để đánh giá", urgent: false });
    expect(reviewExpiryNote("2026-10-09T00:00:00Z", NOW)).toEqual({ text: "Còn 3 ngày để đánh giá", urgent: true });
  });

  it("dưới một ngày thì đếm giờ", () => {
    expect(reviewExpiryNote("2026-10-06T05:30:00Z", NOW)).toEqual({ text: "Còn khoảng 5 giờ để đánh giá", urgent: true });
    expect(reviewExpiryNote("2026-10-06T00:10:00Z", NOW).text).toBe("Còn khoảng 1 giờ để đánh giá");
  });

  it("quá hạn thì nói rõ, không âm", () => {
    expect(reviewExpiryNote("2026-10-05T23:59:59Z", NOW)).toEqual({ text: "Đã hết hạn đánh giá", urgent: true });
  });

  it("mốc không hợp lệ không làm vỡ giao diện", () => {
    expect(reviewExpiryNote("không phải ngày", NOW)).toEqual({ text: "", urgent: false });
  });
});

describe("normalizeReviewContent", () => {
  it("cắt khoảng trắng và đổi nội dung trống thành null", () => {
    expect(normalizeReviewContent("  Rất tốt  ")).toBe("Rất tốt");
    expect(normalizeReviewContent("   \n ")).toBeNull();
    expect(normalizeReviewContent("")).toBeNull();
  });
});

describe("classifyReviewFile", () => {
  it("nhận ảnh và video đúng định dạng, trong giới hạn", () => {
    expect(classifyReviewFile({ type: "image/jpeg", size: 2 * MB })).toEqual({ ok: true, mediaType: "IMAGE" });
    expect(classifyReviewFile({ type: "image/webp", size: REVIEW_MEDIA_RULES.image.maxBytes })).toEqual({ ok: true, mediaType: "IMAGE" });
    expect(classifyReviewFile({ type: "video/mp4", size: 50 * MB })).toEqual({ ok: true, mediaType: "VIDEO" });
  });

  it("từ chối định dạng ngoài danh sách", () => {
    for (const type of ["image/gif", "image/svg+xml", "video/quicktime", "application/pdf", ""]) {
      expect(classifyReviewFile({ type, size: MB })).toMatchObject({ ok: false });
    }
  });

  it("áp giới hạn dung lượng riêng cho ảnh và video", () => {
    const image = classifyReviewFile({ type: "image/png", size: 10 * MB + 1 });
    const video = classifyReviewFile({ type: "video/webm", size: 100 * MB + 1 });
    expect(image).toMatchObject({ ok: false });
    expect(video).toMatchObject({ ok: false });
    if (!image.ok) expect(image.reason).toContain("10 MB");
    if (!video.ok) expect(video.reason).toContain("100 MB");
  });

  it("từ chối tệp rỗng", () => {
    expect(classifyReviewFile({ type: "image/png", size: 0 })).toMatchObject({ ok: false });
  });
});
