// @vitest-environment jsdom
import { act, renderHook, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@/lib/api/review", () => ({ reviewApi: { issueUpload: vi.fn() } }));
vi.mock("@/lib/review-media", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/review-media")>()),
  uploadToStorage: vi.fn(),
}));

import { useReviewMediaUploader } from "@/hooks/use-review-media-uploader";
import { reviewApi } from "@/lib/api/review";
import { REVIEW_MEDIA_RULES, StorageUploadError, uploadToStorage } from "@/lib/review-media";

const issued = (n: number, expiresAt = new Date(Date.now() + 15 * 60_000).toISOString()) => ({
  asset_id: `asset-${n}`,
  expires_at: expiresAt,
  upload: { method: "PUT" as const, url: `https://storage.test/o${n}`, headers: { "Content-Type": "image/png" } },
});

const png = (name = "a.png", size = 1024) => new File([new Uint8Array(size)], name, { type: "image/png" });

beforeEach(() => {
  vi.clearAllMocks();
  URL.createObjectURL = vi.fn(() => "blob:preview");
  URL.revokeObjectURL = vi.fn();
});

describe("useReviewMediaUploader", () => {
  it("xin chỉ dẫn ký, PUT lên storage và giữ asset_id theo thứ tự chọn", async () => {
    vi.mocked(reviewApi.issueUpload).mockResolvedValueOnce(issued(1)).mockResolvedValueOnce(issued(2));
    vi.mocked(uploadToStorage).mockResolvedValue(undefined);
    const { result } = renderHook(() => useReviewMediaUploader());

    act(() => { result.current.add([png("a.png"), png("b.png")]); });
    await waitFor(() => expect(result.current.items.every((item) => item.status === "ready")).toBe(true));

    expect(reviewApi.issueUpload).toHaveBeenCalledWith({ media_type: "IMAGE", content_type: "image/png", byte_size: 1024 });
    expect(result.current.assetIds).toEqual(["asset-1", "asset-2"]);
    expect(result.current.isUploading).toBe(false);
  });

  it("từ chối tệp sai định dạng ngay từ phía client mà không gọi API", () => {
    const { result } = renderHook(() => useReviewMediaUploader());
    let rejections: string[] = [];
    act(() => { rejections = result.current.add([new File(["x"], "doc.pdf", { type: "application/pdf" })]); });

    expect(rejections).toHaveLength(1);
    expect(result.current.items).toHaveLength(0);
    expect(reviewApi.issueUpload).not.toHaveBeenCalled();
  });

  it("không vượt quá số tệp tối đa của một đánh giá", async () => {
    vi.mocked(reviewApi.issueUpload).mockImplementation(async () => issued(Math.random()));
    vi.mocked(uploadToStorage).mockResolvedValue(undefined);
    const { result } = renderHook(() => useReviewMediaUploader());
    const files = Array.from({ length: REVIEW_MEDIA_RULES.maxFiles + 2 }, (_, i) => png(`f${i}.png`));

    let rejections: string[] = [];
    act(() => { rejections = result.current.add(files); });

    expect(result.current.items).toHaveLength(REVIEW_MEDIA_RULES.maxFiles);
    expect(rejections.some((reason) => reason.includes(String(REVIEW_MEDIA_RULES.maxFiles)))).toBe(true);
    await waitFor(() => expect(result.current.isUploading).toBe(false));
  });

  it("upload lỗi thì đánh dấu error và cho thử lại thành công", async () => {
    vi.mocked(reviewApi.issueUpload).mockResolvedValue(issued(1));
    vi.mocked(uploadToStorage).mockRejectedValueOnce(new StorageUploadError(403)).mockResolvedValueOnce(undefined);
    const { result } = renderHook(() => useReviewMediaUploader());

    act(() => { result.current.add([png()]); });
    await waitFor(() => expect(result.current.items[0]?.status).toBe("error"));
    expect(result.current.hasError).toBe(true);
    expect(result.current.assetIds).toEqual([]);

    act(() => { result.current.retry(result.current.items[0].key); });
    await waitFor(() => expect(result.current.items[0]?.status).toBe("ready"));
    expect(result.current.hasError).toBe(false);
  });

  it("ensureFresh đánh dấu lỗi tệp đã quá hạn gắn và loại asset_id khỏi danh sách gửi", async () => {
    vi.mocked(reviewApi.issueUpload).mockResolvedValue(issued(1, new Date(Date.now() - 1000).toISOString()));
    vi.mocked(uploadToStorage).mockResolvedValue(undefined);
    const { result } = renderHook(() => useReviewMediaUploader());

    act(() => { result.current.add([png()]); });
    await waitFor(() => expect(result.current.items[0]?.status).toBe("ready"));

    let fresh = true;
    act(() => { fresh = result.current.ensureFresh(); });
    expect(fresh).toBe(false);
    expect(result.current.items[0].status).toBe("error");
    expect(result.current.assetIds).toEqual([]);
  });

  it("gỡ tệp thì thu hồi object URL xem trước", async () => {
    vi.mocked(reviewApi.issueUpload).mockResolvedValue(issued(1));
    vi.mocked(uploadToStorage).mockResolvedValue(undefined);
    const { result } = renderHook(() => useReviewMediaUploader());

    act(() => { result.current.add([png()]); });
    await waitFor(() => expect(result.current.items[0]?.status).toBe("ready"));
    act(() => { result.current.remove(result.current.items[0].key); });

    expect(result.current.items).toHaveLength(0);
    expect(URL.revokeObjectURL).toHaveBeenCalledWith("blob:preview");
  });
});
