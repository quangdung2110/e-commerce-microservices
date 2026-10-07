"use client";

import { useSyncExternalStore } from "react";

const noopSubscribe = () => () => {};

/**
 * `true` sau khi đã hydrate ở client, `false` khi render phía server và trong lần
 * hydrate đầu. Dùng cho thứ chỉ biết được ở trình duyệt (theme thực tế,
 * `location.hash`) mà không cần `setState` trong `useEffect`.
 */
export function useIsClient(): boolean {
  return useSyncExternalStore(
    noopSubscribe,
    () => true,
    () => false
  );
}

/**
 * Giá trị đọc từ `location.hash`, cập nhật khi hash đổi. Trả `undefined` khi chưa
 * ở client — khác với `null` ("đã đọc, không có") để UI hiện trạng thái chờ đúng.
 */
export function useLocationHashValue<T>(read: () => T): T | undefined {
  return useSyncExternalStore(
    (onChange) => {
      window.addEventListener("hashchange", onChange);
      return () => window.removeEventListener("hashchange", onChange);
    },
    read,
    () => undefined
  );
}
