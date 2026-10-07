import type { AxiosRequestConfig, AxiosResponse } from "axios";

import { api } from "@/lib/api/client";
import { problemStatus } from "@/lib/api/problem";

/**
 * Optimistic concurrency cho API quản trị (Product, Promotion).
 *
 * Mỗi GET/lệnh thành công trả `ETag` đại diện revision của **aggregate** (ví dụ
 * `"catalog-revision-42"` của Product, kể cả khi lệnh chỉ sửa một SKU). Lệnh sửa
 * gửi lại ETag đó trong `If-Match`; nếu người khác đã sửa trước, server trả `412`
 * và không ghi gì. UI không được tự ghi đè — phải cho người dùng tải lại.
 */

/** Dữ liệu kèm ETag của lần đọc/ghi gần nhất. */
export interface Versioned<T> {
  data: T;
  /** `null` khi response không có ETag (không nên xảy ra với API có If-Match). */
  etag: string | null;
}

/** Đọc ETag nguyên văn (kể cả dấu nháy kép) từ response. */
export function readEtag(response: Pick<AxiosResponse, "headers">): string | null {
  const value: unknown = response.headers?.["etag"];
  return typeof value === "string" && value.length > 0 ? value : null;
}

export function ifMatchHeaders(etag: string): Record<string, string> {
  return { "If-Match": etag };
}

/** Bọc một response thành `Versioned`. */
export function toVersioned<T>(response: AxiosResponse<T>): Versioned<T> {
  return { data: response.data, etag: readEtag(response) };
}

/** GET kèm ETag. */
export async function getVersioned<T>(
  url: string,
  config?: AxiosRequestConfig
): Promise<Versioned<T>> {
  return toVersioned(await api.get<T>(url, config));
}

/**
 * Lỗi do ETag đã cũ (`412 Precondition Failed`). Mã cụ thể khác nhau theo
 * service (`PRODUCT_REVISION_MISMATCH`, `CAMPAIGN_REVISION_MISMATCH`) nhưng status
 * luôn là 412.
 */
export function isStaleVersion(error: unknown): boolean {
  return problemStatus(error) === 412;
}
