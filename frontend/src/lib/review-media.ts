import type { ReviewMediaType, UploadInstruction } from "@/lib/api/review";

/**
 * Giới hạn media của review. Nguồn: `application.yml` của Review
 * (`review.media.*`, `max-media-per-review`). Server vẫn là bên quyết định; số liệu
 * ở đây chỉ để từ chối sớm những tệp chắc chắn bị `400`/`429`.
 */
export const REVIEW_MEDIA_RULES = {
  maxFiles: 9,
  image: { types: ["image/jpeg", "image/png", "image/webp"], maxBytes: 10 * 1024 * 1024 },
  video: { types: ["video/mp4", "video/webm"], maxBytes: 100 * 1024 * 1024 },
} as const;

/** Giá trị cho thuộc tính `accept` của `<input type="file">`. */
export const REVIEW_MEDIA_ACCEPT = [...REVIEW_MEDIA_RULES.image.types, ...REVIEW_MEDIA_RULES.video.types].join(",");

export type MediaVerdict =
  | { ok: true; mediaType: ReviewMediaType }
  | { ok: false; reason: string };

function megabytes(bytes: number): string {
  return `${Math.round(bytes / (1024 * 1024))} MB`;
}

/** Phân loại và kiểm tra một tệp người dùng chọn. */
export function classifyReviewFile(file: { type: string; size: number }): MediaVerdict {
  if (file.size <= 0) return { ok: false, reason: "Tệp trống." };

  const isImage = (REVIEW_MEDIA_RULES.image.types as readonly string[]).includes(file.type);
  const isVideo = (REVIEW_MEDIA_RULES.video.types as readonly string[]).includes(file.type);
  if (!isImage && !isVideo) {
    return { ok: false, reason: "Chỉ nhận ảnh JPEG, PNG, WebP hoặc video MP4, WebM." };
  }

  const rule = isImage ? REVIEW_MEDIA_RULES.image : REVIEW_MEDIA_RULES.video;
  if (file.size > rule.maxBytes) {
    return {
      ok: false,
      reason: `${isImage ? "Ảnh" : "Video"} vượt quá ${megabytes(rule.maxBytes)}.`,
    };
  }
  return { ok: true, mediaType: isImage ? "IMAGE" : "VIDEO" };
}

export class StorageUploadError extends Error {
  constructor(public readonly status: number | null) {
    super(status === null ? "Storage upload failed before a response" : `Storage upload failed with HTTP ${status}`);
    this.name = "StorageUploadError";
  }
}

/**
 * Tải byte thẳng lên storage theo chỉ dẫn đã ký (`PUT`, ghi một lần, không ghi đè).
 *
 * Dùng XMLHttpRequest vì `fetch` không báo tiến độ upload. Chỉ gửi đúng các header
 * mà chữ ký yêu cầu; thêm hay đổi URL/host làm hỏng SigV4. Không gửi cookie hay
 * token của ứng dụng tới storage.
 */
export function uploadToStorage(
  instruction: UploadInstruction,
  file: Blob,
  options: { signal?: AbortSignal; onProgress?: (fraction: number) => void } = {}
): Promise<void> {
  return new Promise((resolve, reject) => {
    if (options.signal?.aborted) {
      reject(new DOMException("Upload aborted", "AbortError"));
      return;
    }
    const xhr = new XMLHttpRequest();
    const abort = () => xhr.abort();
    const cleanup = () => options.signal?.removeEventListener("abort", abort);
    xhr.open(instruction.method, instruction.url);
    for (const [name, value] of Object.entries(instruction.headers)) {
      // Content-Length do trình duyệt đặt và bị cấm đặt thủ công.
      if (name.toLowerCase() !== "content-length") xhr.setRequestHeader(name, value);
    }

    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable) options.onProgress?.(event.loaded / event.total);
    };
    xhr.onload = () => {
      cleanup();
      if (xhr.status >= 200 && xhr.status < 300) {
        options.onProgress?.(1);
        resolve();
      } else {
        reject(new StorageUploadError(xhr.status));
      }
    };
    xhr.onerror = xhr.ontimeout = () => { cleanup(); reject(new StorageUploadError(null)); };
    xhr.onabort = () => { cleanup(); reject(new DOMException("Upload aborted", "AbortError")); };

    options.signal?.addEventListener("abort", abort, { once: true });
    if (options.signal?.aborted) {
      cleanup();
      reject(new DOMException("Upload aborted", "AbortError"));
      return;
    }
    xhr.send(file);
  });
}
