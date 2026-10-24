"use client";

import { useCallback, useEffect, useRef, useState } from "react";

import { problemCode, problemMessage, retryAfterSeconds } from "@/lib/api/problem";
import { reviewApi, type ReviewMediaType } from "@/lib/api/review";
import { classifyReviewFile, REVIEW_MEDIA_RULES, StorageUploadError, uploadToStorage } from "@/lib/review-media";
import { useAuthStore } from "@/store/useAuthStore";

export type MediaItemStatus = "uploading" | "ready" | "error";

export interface MediaItem {
  key: string;
  file: File;
  mediaType: ReviewMediaType;
  /** Object URL của tệp cục bộ để xem trước; thu hồi khi gỡ hoặc rời trang. */
  previewUrl: string;
  status: MediaItemStatus;
  progress: number;
  assetId?: string;
  /** Hạn gắn asset vào review (epoch ms); sau đó Review dọn asset. */
  expiresAt?: number;
  error?: string;
}

let keySeed = 0;
const nextKey = () => `media-${Date.now()}-${keySeed++}`;

/**
 * Upload media của review theo contract: xin chỉ dẫn ký → `PUT` thẳng vào storage →
 * gửi `asset_id` khi tạo/sửa review. Không có API xác minh hay poll; Review tự kiểm
 * tra object lúc gắn, nên không có trạng thái "đã xác minh" ở đây.
 *
 * Mỗi tệp tải ngay khi chọn. Asset chưa gắn hết hạn sau `expires_at` (mặc định 15
 * phút); `ensureFresh()` đánh dấu lỗi những tệp đã quá hạn để người dùng tải lại thay
 * vì nhận `MEDIA_NOT_VERIFIED` sau khi bấm gửi.
 */
export function useReviewMediaUploader() {
  const accountId = useAuthStore((state) => state.claims?.sub);
  const [items, setItems] = useState<MediaItem[]>([]);
  const itemsRef = useRef<MediaItem[]>([]);
  const controllers = useRef(new Map<string, AbortController>());

  const commit = useCallback((updater: (current: MediaItem[]) => MediaItem[]) => {
    itemsRef.current = updater(itemsRef.current);
    setItems(itemsRef.current);
  }, []);

  const patch = useCallback(
    (key: string, changes: Partial<MediaItem>) =>
      commit((current) => current.map((item) => (item.key === key ? { ...item, ...changes } : item))),
    [commit]
  );

  const start = useCallback(
    async (item: MediaItem) => {
      const controller = new AbortController();
      controllers.current.set(item.key, controller);
      patch(item.key, { status: "uploading", progress: 0, error: undefined });
      try {
        const issued = await reviewApi.issueUpload({
          media_type: item.mediaType,
          content_type: item.file.type,
          byte_size: item.file.size,
        }, controller.signal);
        if (controller.signal.aborted) return;
        await uploadToStorage(issued.upload, item.file, {
          signal: controller.signal,
          onProgress: (fraction) => patch(item.key, { progress: fraction }),
        });
        if (controller.signal.aborted) return;
        patch(item.key, {
          status: "ready",
          progress: 1,
          assetId: issued.asset_id,
          expiresAt: Date.parse(issued.expires_at),
        });
      } catch (error) {
        if (controller.signal.aborted) return;
        patch(item.key, { status: "error", error: uploadErrorMessage(error) });
      } finally {
        if (controllers.current.get(item.key) === controller) controllers.current.delete(item.key);
      }
    },
    [patch]
  );

  /** Thêm tệp; trả về danh sách lý do từ chối (để hiển thị), tệp hợp lệ được tải ngay. */
  const add = useCallback(
    (files: File[]): string[] => {
      const rejections: string[] = [];
      let slots = REVIEW_MEDIA_RULES.maxFiles - itemsRef.current.length;
      const accepted: MediaItem[] = [];
      for (const file of files) {
        if (slots <= 0) {
          rejections.push(`Mỗi đánh giá chỉ đính kèm tối đa ${REVIEW_MEDIA_RULES.maxFiles} ảnh hoặc video.`);
          break;
        }
        const verdict = classifyReviewFile(file);
        if (!verdict.ok) {
          rejections.push(`${file.name}: ${verdict.reason}`);
          continue;
        }
        accepted.push({
          key: nextKey(),
          file,
          mediaType: verdict.mediaType,
          previewUrl: URL.createObjectURL(file),
          status: "uploading",
          progress: 0,
        });
        slots -= 1;
      }
      if (accepted.length) {
        commit((current) => [...current, ...accepted]);
        accepted.forEach((item) => void start(item));
      }
      return rejections;
    },
    [commit, start]
  );

  const remove = useCallback(
    (key: string) => {
      controllers.current.get(key)?.abort();
      const target = itemsRef.current.find((item) => item.key === key);
      if (target) URL.revokeObjectURL(target.previewUrl);
      commit((current) => current.filter((item) => item.key !== key));
    },
    [commit]
  );

  const retry = useCallback(
    (key: string) => {
      const target = itemsRef.current.find((item) => item.key === key);
      if (target && target.status === "error") void start(target);
    },
    [start]
  );

  const reset = useCallback(() => {
    controllers.current.forEach((controller) => controller.abort());
    controllers.current.clear();
    itemsRef.current.forEach((item) => URL.revokeObjectURL(item.previewUrl));
    commit(() => []);
  }, [commit]);

  /** Đánh dấu lỗi các tệp đã quá hạn gắn; trả về true nếu mọi tệp còn dùng được. */
  const ensureFresh = useCallback(() => {
    const now = Date.now();
    let allFresh = true;
    commit((current) =>
      current.map((item) => {
        if (item.status === "ready" && item.expiresAt !== undefined && item.expiresAt <= now) {
          allFresh = false;
          return { ...item, status: "error", assetId: undefined, error: "Tệp đã hết hạn gắn vào đánh giá. Hãy tải lên lại." };
        }
        return item;
      })
    );
    return allFresh;
  }, [commit]);

  useEffect(() => {
    const pending = controllers.current;
    setItems(itemsRef.current);
    return () => {
      pending.forEach((controller) => controller.abort());
      pending.clear();
      itemsRef.current.forEach((item) => URL.revokeObjectURL(item.previewUrl));
      itemsRef.current = [];
    };
  }, [accountId]);

  const isUploading = items.some((item) => item.status === "uploading");
  const hasError = items.some((item) => item.status === "error");
  const assetIds = items.flatMap((item) => (item.status === "ready" && item.assetId ? [item.assetId] : []));

  return { items, add, remove, retry, reset, ensureFresh, isUploading, hasError, assetIds };
}

function uploadErrorMessage(error: unknown): string {
  if (error instanceof StorageUploadError) {
    return "Không tải được tệp lên kho lưu trữ. Hãy kiểm tra đường truyền rồi thử lại.";
  }
  if (problemCode(error) === "MEDIA_QUOTA_EXCEEDED") {
    const wait = retryAfterSeconds(error);
    return wait
      ? `Bạn đã tải lên quá nhiều tệp chưa dùng. Thử lại sau khoảng ${wait} giây hoặc gỡ bớt tệp.`
      : "Bạn đã tải lên quá nhiều tệp chưa dùng. Hãy gỡ bớt tệp rồi thử lại.";
  }
  return problemMessage(error);
}
