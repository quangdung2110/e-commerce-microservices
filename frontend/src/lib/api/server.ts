import "server-only";

import { isProblem, type Problem } from "@/lib/api/problem";

/**
 * Gọi API công khai từ Server Component (ADR-002).
 *
 * Gọi thẳng API Gateway, không đi qua rewrite của Next và không gắn danh tính:
 * server của Next không có access token của người dùng (token chỉ nằm trong bộ
 * nhớ trình duyệt). Vì vậy chỉ dùng cho route công khai của gateway — Search v2,
 * Product Detail BFF, danh sách review đã duyệt. API cần đăng nhập phải gọi từ
 * Client Component qua `lib/api/client.ts`.
 */

/** Cùng giá trị mặc định với `next.config.ts`. */
const API_UPSTREAM = process.env.API_UPSTREAM ?? "http://api-gateway:80";

export class ServerApiError extends Error {
  constructor(
    readonly status: number,
    readonly problem: Problem | null
  ) {
    super(problem?.code ?? `HTTP ${status}`);
    this.name = "ServerApiError";
  }
}

type QueryValue = string | number | boolean | null | undefined;

export interface ServerGetOptions {
  /** Tham số query; giá trị mảng được lặp lại (`?filter=a&filter=b`). */
  query?: Record<string, QueryValue | readonly QueryValue[]>;
  /** Số giây trước khi cache ISR được làm mới. `0` = không cache. */
  revalidate?: number;
  /** Tag để làm mới theo yêu cầu bằng `revalidateTag`. */
  tags?: string[];
}

function buildUrl(path: string, query: ServerGetOptions["query"]): string {
  const url = new URL(path, API_UPSTREAM);
  for (const [key, raw] of Object.entries(query ?? {})) {
    const values = Array.isArray(raw) ? raw : [raw];
    for (const value of values) {
      if (value !== null && value !== undefined && value !== "") {
        url.searchParams.append(key, String(value));
      }
    }
  }
  return url.toString();
}

/**
 * GET một route công khai. Ném `ServerApiError` khi status không phải 2xx; body
 * lỗi problem+json được giữ lại để page quyết định `notFound()` hay hiện lỗi.
 */
export async function serverGet<T>(path: string, options: ServerGetOptions = {}): Promise<T> {
  const { query, revalidate, tags } = options;

  const response = await fetch(buildUrl(path, query), {
    signal: AbortSignal.timeout(8000),
    headers: {
      Accept: "application/json",
      "X-Request-Id": crypto.randomUUID(),
    },
    ...(revalidate === 0
      ? { cache: "no-store" as const }
      : { next: { revalidate, tags } }),
  });

  if (!response.ok) {
    let problem: Problem | null = null;
    try {
      const body: unknown = await response.json();
      if (isProblem(body)) problem = body;
    } catch {
      /* body rỗng hoặc không phải JSON — giữ problem = null */
    }
    throw new ServerApiError(response.status, problem);
  }

  return (await response.json()) as T;
}

/** Như `serverGet` nhưng trả `null` khi 404, để page gọi `notFound()`. */
export async function serverGetOrNull<T>(
  path: string,
  options: ServerGetOptions = {}
): Promise<T | null> {
  try {
    return await serverGet<T>(path, options);
  } catch (error) {
    if (error instanceof ServerApiError && error.status === 404) return null;
    throw error;
  }
}
