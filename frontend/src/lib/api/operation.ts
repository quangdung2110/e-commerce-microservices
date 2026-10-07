import type { AxiosResponse } from "axios";

import { parseRetryAfter } from "@/lib/api/problem";

/**
 * Theo dõi một lệnh bất đồng bộ tới khi có kết quả cuối.
 *
 * Nhiều API trả `202 Accepted` kèm `Location` (nơi đọc trạng thái) và có thể có
 * `Retry-After`: order operation, checkout submission, review eligibility,
 * reindex run, claim voucher `CLAIM_PENDING`. Hình dạng trạng thái mỗi nơi một
 * khác, nên caller tự nói thế nào là "xong"; hàm này chỉ lo nhịp poll, tôn trọng
 * `Retry-After`, dừng khi bị huỷ và dừng khi quá hạn.
 */

export interface PollRead<T> {
  data: T;
  /** Số giây server gợi ý chờ trước lần đọc tiếp theo (`Retry-After`). */
  retryAfterSeconds: number | null;
}

export interface PollOptions<T> {
  /** Đọc trạng thái một lần. */
  read: (signal: AbortSignal) => Promise<PollRead<T>>;
  /** `true` khi trạng thái đã là kết quả cuối. */
  isDone: (data: T) => boolean;
  /** Huỷ từ bên ngoài, ví dụ khi component unmount. */
  signal?: AbortSignal;
  /** Nhịp chờ đầu tiên khi server không gợi ý (ms). */
  initialDelayMs?: number;
  /** Nhịp chờ tối đa khi tự giãn (ms). */
  maxDelayMs?: number;
  /** Tổng thời gian tối đa trước khi bỏ cuộc (ms). */
  timeoutMs?: number;
  /** Chỉ để test thay thế đồng hồ. */
  sleep?: (ms: number, signal: AbortSignal) => Promise<void>;
  now?: () => number;
}

/**
 * Hết thời gian mà lệnh vẫn chưa xong. Đây **không** phải lệnh thất bại: server
 * có thể vẫn đang xử lý, nên UI phải nói "đang xử lý, kiểm tra lại sau" và
 * tuyệt đối không mời người dùng gửi lại lệnh mới.
 */
export class PollTimeoutError<T> extends Error {
  constructor(readonly lastData: T | undefined) {
    super("Operation still in progress");
    this.name = "PollTimeoutError";
  }
}

export class PollAbortedError extends Error {
  constructor() {
    super("Polling aborted");
    this.name = "PollAbortedError";
  }
}

function defaultSleep(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal.aborted) return reject(new PollAbortedError());
    const id = setTimeout(() => {
      signal.removeEventListener("abort", onAbort);
      resolve();
    }, ms);
    function onAbort() {
      clearTimeout(id);
      reject(new PollAbortedError());
    }
    signal.addEventListener("abort", onAbort, { once: true });
  });
}

export async function pollUntil<T>(options: PollOptions<T>): Promise<T> {
  const {
    read,
    isDone,
    initialDelayMs = 1000,
    maxDelayMs = 10_000,
    timeoutMs = 60_000,
    sleep = defaultSleep,
    now = Date.now,
  } = options;

  const controller = new AbortController();
  const outer = options.signal;
  if (outer?.aborted) throw new PollAbortedError();
  const forwardAbort = () => controller.abort();
  outer?.addEventListener("abort", forwardAbort, { once: true });

  const deadline = now() + timeoutMs;
  let delay = initialDelayMs;
  let last: T | undefined;

  try {
    for (;;) {
      const result = await read(controller.signal);
      if (controller.signal.aborted) throw new PollAbortedError();
      last = result.data;
      if (isDone(result.data)) return result.data;

      const suggested =
        result.retryAfterSeconds !== null ? result.retryAfterSeconds * 1000 : delay;
      const remaining = deadline - now();
      if (remaining <= 0) throw new PollTimeoutError(last);

      await sleep(Math.min(suggested, remaining), controller.signal);
      // Server không gợi ý thì tự giãn nhịp để không dồn tải khi xử lý lâu.
      if (result.retryAfterSeconds === null) delay = Math.min(delay * 1.5, maxDelayMs);
    }
  } finally {
    outer?.removeEventListener("abort", forwardAbort);
  }
}

/** Dựng `PollRead` từ một response axios. */
export function toPollRead<T>(response: Pick<AxiosResponse<T>, "data" | "headers">): PollRead<T> {
  return {
    data: response.data,
    retryAfterSeconds: parseRetryAfter(response.headers?.["retry-after"]),
  };
}

/** Đường dẫn trạng thái trong header `Location` của một response `202`. */
export function operationLocation(response: Pick<AxiosResponse, "headers">): string | null {
  const value: unknown = response.headers?.["location"];
  return typeof value === "string" && value.length > 0 ? value : null;
}
