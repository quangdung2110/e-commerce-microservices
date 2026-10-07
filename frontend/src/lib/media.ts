/**
 * URL công khai của media Product từ `storage_key`.
 *
 * Storefront nhận URL đã dựng sẵn từ Product Detail BFF và Search; chỉ admin đọc
 * Product API trực tiếp, nơi media chỉ có `storage_key`. Cách dựng phải giống BFF
 * (docs/product-detail-bff/decisions/ADR-005): base URL + `storage_key` đã
 * percent-encode **từng segment**, giữ nguyên dấu `/`.
 */
const MEDIA_BASE_URL = (process.env.NEXT_PUBLIC_MEDIA_BASE_URL ?? "/media").replace(/\/+$/, "");

export function mediaUrl(storageKey: string | null | undefined): string | null {
  if (!storageKey) return null;
  const path = storageKey
    .split("/")
    .filter((segment) => segment.length > 0)
    .map(encodeURIComponent)
    .join("/");
  return path ? `${MEDIA_BASE_URL}/${path}` : null;
}
