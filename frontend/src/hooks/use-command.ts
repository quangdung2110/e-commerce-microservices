"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useMutation, type UseMutationOptions } from "@tanstack/react-query";

import { newCommandId } from "@/lib/api/command";
import { isCommandOutcomeUnknown } from "@/lib/admin-command";
import { isRetryable, retryAfterSeconds } from "@/lib/api/problem";

export interface CommandContext {
  /** Dùng làm `Idempotency-Key` (và `X-Causation-ID` với Inventory). */
  commandId: string;
}

type CommandOptions<TData, TVariables> = Omit<
  UseMutationOptions<TData, unknown, TVariables>,
  "mutationFn"
> & {
  mutationFn: (variables: TVariables, context: CommandContext) => Promise<TData>;
  /**
   * Gọi khi mutationFn trả về (server đã nhận lệnh). `true` nghĩa là kết quả cuối **chưa rõ** —
   * ví dụ poll operation hết hạn hoặc operation chờ kiểm tra thủ công — nên lần gửi sau phải là
   * replay của đúng ý định này (giữ command ID), không phải một lệnh mới.
   */
  isOutcomeUnresolved?: (data: TData) => boolean;
};

/**
 * `useMutation` cho lệnh có idempotency key.
 *
 * Một command ID đại diện cho **một ý định** của người dùng, không phải một lần
 * bấm nút:
 *
 * - Lần gửi đầu sinh ID mới.
 * - Thất bại *có thể thử lại* (mất mạng, 429, 5xx, `retryable: true`) → lần gửi
 *   sau dùng lại đúng ID đó. Request trước có thể đã tới server; gửi ID mới lúc
 *   này có nguy cơ thực hiện hai lần.
 * - Thành công, hoặc thất bại không thể thử lại (400, 409, 422…) → lần gửi sau
 *   là ý định mới, sinh ID mới. Người dùng thường đã sửa dữ liệu, dùng lại ID cũ
 *   với payload khác sẽ bị server từ chối.
 * - Ngoại lệ của mục trên: lệnh bất đồng bộ đã được nhận nhưng `isOutcomeUnresolved(data)`
 *   trả `true` (poll hết hạn, chờ kiểm tra thủ công) thì vẫn giữ ID, vì server có thể
 *   đang xử lý đúng ý định đó.
 *
 * Gọi `startNewIntent()` khi mở lại dialog/form để chắc chắn bắt đầu ý định mới.
 */
export function useCommand<TData = unknown, TVariables = void>(
  options: CommandOptions<TData, TVariables>
) {
  const { mutationFn, isOutcomeUnresolved, onSuccess, onError, ...rest } = options;
  const pendingId = useRef<string | null>(null);
  const [retryDeadline, setRetryDeadline] = useState<number | null>(null);
  const [retryDelaySeconds, setRetryDelaySeconds] = useState(0);

  const clearRetryDelay = useCallback(() => {
    setRetryDeadline(null);
    setRetryDelaySeconds(0);
  }, []);

  useEffect(() => {
    if (retryDeadline === null) return;

    const updateRemaining = () => {
      const remaining = Math.max(0, Math.ceil((retryDeadline - Date.now()) / 1000));
      setRetryDelaySeconds(remaining);
      if (remaining === 0) setRetryDeadline(null);
    };

    updateRemaining();
    const timer = window.setInterval(updateRemaining, 250);
    return () => window.clearInterval(timer);
  }, [retryDeadline]);

  const startNewIntent = useCallback(() => {
    pendingId.current = null;
    clearRetryDelay();
  }, [clearRetryDelay]);

  const mutation = useMutation<TData, unknown, TVariables>({
    ...rest,
    mutationFn: (variables) => {
      pendingId.current ??= newCommandId();
      return mutationFn(variables, { commandId: pendingId.current });
    },
    onSuccess: (...args) => {
      if (!isOutcomeUnresolved?.(args[0])) {
        pendingId.current = null;
        clearRetryDelay();
      }
      return onSuccess?.(...args);
    },
    onError: (...args) => {
      if (isRetryable(args[0]) || isCommandOutcomeUnknown(args[0])) {
        const delay = retryAfterSeconds(args[0]);
        if (delay !== null) {
          setRetryDelaySeconds(delay);
          setRetryDeadline(Date.now() + delay * 1000);
        } else {
          clearRetryDelay();
        }
      } else {
        pendingId.current = null;
        clearRetryDelay();
      }
      return onError?.(...args);
    },
  });

  return {
    ...mutation,
    startNewIntent,
    retryDelaySeconds,
    isRetryDelayed: retryDelaySeconds > 0,
  };
}
