import { AxiosError } from "axios";

import { PROBLEM_MESSAGES, STATUS_MESSAGES } from "@/lib/api/problem-messages";

/**
 * Body lỗi RFC 9457 dùng chung cho mọi service của hệ thống.
 *
 * Schema chuẩn: `contracts/shared/json-schema/v1/problem.schema.json`. Trường
 * `code` là hợp đồng ổn định giữa backend và frontend; `detail` là tiếng Anh
 * viết cho lập trình viên và KHÔNG được hiển thị cho người dùng.
 */
export interface ProblemFieldError {
  field: string;
  message: string;
}

export interface Problem {
  type: string;
  title: string;
  status: number;
  detail: string;
  instance: string;
  code: string;
  trace_id?: string;
  retryable?: boolean;
  errors?: ProblemFieldError[];
}

const FALLBACK = "Đã có lỗi xảy ra. Vui lòng thử lại.";

/** Nhận diện một body có đúng hình dạng problem+json hay không. */
export function isProblem(value: unknown): value is Problem {
  if (typeof value !== "object" || value === null) return false;
  const candidate = value as Partial<Problem>;
  return typeof candidate.code === "string" && typeof candidate.status === "number";
}

/**
 * Rút Problem ra khỏi một lỗi bất kỳ; trả `null` nếu đó không phải lỗi HTTP có
 * body problem+json. Lỗi xác thực ở edge cũng là problem+json: `gateway-auth`
 * trả `UNAUTHORIZED`, `TOKEN_INVALID`, `TOKEN_REVOKED` hoặc
 * `DEPENDENCY_UNAVAILABLE`.
 */
export function getProblem(error: unknown): Problem | null {
  if (typeof error === "object" && error !== null && "problem" in error) {
    return isProblem(error.problem) ? error.problem : null;
  }
  if (!(error instanceof AxiosError) || !error.response) return null;
  const body: unknown = error.response.data;
  return isProblem(body) ? body : null;
}

/**
 * HTTP status của lỗi, kể cả khi body không phải problem+json (ví dụ 502 từ
 * gateway). `null` nghĩa là request chưa tới được server.
 */
export function problemStatus(error: unknown): number | null {
  if (typeof error === "object" && error !== null && "status" in error && typeof error.status === "number") return error.status;
  if (!(error instanceof AxiosError)) return null;
  return error.response?.status ?? null;
}

/** Mã lỗi ổn định của backend, dùng để rẽ nhánh điều hướng. */
export function problemCode(error: unknown): string | null {
  return getProblem(error)?.code ?? null;
}

/**
 * Câu hiển thị cho người dùng. Thứ tự tra: `code` → HTTP status → câu chung.
 * Không bao giờ trả `detail` thô của backend.
 */
export function problemMessage(error: unknown, fallback = FALLBACK): string {
  if (error instanceof AxiosError && !error.response) {
    return "Không kết nối được tới máy chủ. Vui lòng kiểm tra đường truyền.";
  }

  const code = problemCode(error);
  if (code && PROBLEM_MESSAGES[code]) return PROBLEM_MESSAGES[code];

  const status = problemStatus(error);
  if (status !== null && STATUS_MESSAGES[status]) return STATUS_MESSAGES[status];
  if (status !== null && status >= 500) return PROBLEM_MESSAGES.INTERNAL_ERROR;

  return fallback;
}

/** Mã tra cứu để người dùng đọc cho bộ phận hỗ trợ khi gặp lỗi lạ. */
export function problemTraceId(error: unknown): string | undefined {
  return getProblem(error)?.trace_id;
}

/**
 * Có nên thử lại **đúng lệnh đó** (cùng idempotency key) hay không. Ưu tiên cờ
 * `retryable` của backend; thiếu cờ thì suy từ status.
 */
export function isRetryable(error: unknown): boolean {
  const problem = getProblem(error);
  if (typeof problem?.retryable === "boolean") return problem.retryable;
  const status = problemStatus(error);
  if (status === null) return error instanceof AxiosError;
  return status === 429 || status === 502 || status === 503 || status === 504;
}

/**
 * Số giây phải chờ trước khi thử lại, đọc từ header `Retry-After` của response
 * 202/423/429/503. Trả `null` khi backend không gợi ý.
 */
export function retryAfterSeconds(error: unknown): number | null {
  if (!(error instanceof AxiosError)) return null;
  const header: unknown = error.response?.headers?.["retry-after"];
  return parseRetryAfter(header);
}

/** Đọc giá trị `Retry-After` dạng số giây; dạng HTTP-date không được backend dùng. */
export function parseRetryAfter(header: unknown): number | null {
  if (typeof header !== "string" && typeof header !== "number") return null;
  const seconds = Number.parseInt(String(header), 10);
  return Number.isFinite(seconds) && seconds > 0 ? seconds : null;
}

/**
 * Gắn `errors[]` của problem vào đúng field của react-hook-form.
 *
 * Trả về danh sách field không khớp với form hiện tại — caller nên dồn chúng lên
 * banner chung thay vì bỏ im lặng.
 */
export function applyFieldErrors<TField extends string>(
  error: unknown,
  knownFields: readonly TField[],
  setError: (field: TField, message: string) => void
): string[] {
  const problem = getProblem(error);
  if (!problem?.errors?.length) return [];

  const unmatched: string[] = [];
  for (const fieldError of problem.errors) {
    if ((knownFields as readonly string[]).includes(fieldError.field)) {
      setError(fieldError.field as TField, fieldError.message);
    } else {
      unmatched.push(fieldError.field);
    }
  }
  return unmatched;
}
