/**
 * Đổi `deep_link` của một thông báo thành đường dẫn nội bộ an toàn để `router.push`.
 *
 * Notification lắp link từ `frontend.base_url` nên thường là URL tuyệt đối. Link chỉ
 * được theo khi cùng origin với trang đang mở: một link tuyệt đối khác origin (cấu
 * hình sai, hoặc nội dung template bị can thiệp) không bao giờ được dẫn người dùng
 * ra khỏi ứng dụng từ một cú bấm trong hộp thư. Trả `null` khi không an toàn hoặc
 * không có link; caller khi đó chỉ đánh dấu đã đọc.
 */
export function internalPathOf(deepLink: string | null | undefined, currentOrigin: string): string | null {
  if (!deepLink) return null;
  const value = deepLink.trim();
  if (!value) return null;

  // Đường dẫn tương đối một gạch chéo. `//host` là URL cùng giao thức tới host khác.
  if (value.startsWith("/") && !value.startsWith("//") && !value.includes("\\")) return value;

  let url: URL;
  try {
    url = new URL(value);
  } catch {
    return null;
  }
  if (url.origin !== currentOrigin) return null;
  return `${url.pathname}${url.search}${url.hash}`;
}
