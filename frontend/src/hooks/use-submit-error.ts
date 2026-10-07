"use client";

import { useCallback, useEffect, useState } from "react";

import {
  applyFieldErrors,
  problemCode,
  problemMessage,
  problemTraceId,
  retryAfterSeconds,
} from "@/lib/api/problem";

interface CaptureOptions<TField extends string> {
  /** Các field có thật trong form, để gắn `errors[]` của backend vào đúng chỗ. */
  fields?: readonly TField[];
  setFieldError?: (field: TField, message: string) => void;
  /** Ghi đè câu thông báo cho một số mã lỗi ở ngữ cảnh cụ thể. */
  overrides?: Record<string, string>;
}

/**
 * Gom việc xử lý lỗi submit của một form: câu thông báo tiếng Việt, mã tra cứu,
 * đồng hồ đếm ngược khi bị rate limit, và việc gắn lỗi vào từng field.
 *
 * Dùng chung cho mọi form gọi API để năm form không tự nghĩ ra năm cách khác nhau.
 */
export function useSubmitError() {
  const [message, setMessage] = useState<string | null>(null);
  const [traceId, setTraceId] = useState<string | undefined>(undefined);
  const [code, setCode] = useState<string | null>(null);
  const [cooldown, setCooldown] = useState(0);

  // Một timeout cho mỗi nhịp: effect chạy lại theo `cooldown` nên không cần ref
  // giữ interval, và không có nguy cơ để sót timer khi component unmount.
  useEffect(() => {
    if (cooldown <= 0) return;
    const id = setTimeout(() => setCooldown((current) => current - 1), 1000);
    return () => clearTimeout(id);
  }, [cooldown]);

  const reset = useCallback(() => {
    setMessage(null);
    setTraceId(undefined);
    setCode(null);
  }, []);

  const capture = useCallback(
    <TField extends string>(error: unknown, options: CaptureOptions<TField> = {}) => {
      const errorCode = problemCode(error);
      setCode(errorCode);
      setTraceId(problemTraceId(error));

      // Rate limit / khoá tài khoản: backend nói rõ phải chờ bao lâu.
      const wait = retryAfterSeconds(error);
      if (wait) setCooldown(wait);

      const override = errorCode ? options.overrides?.[errorCode] : undefined;
      setMessage(override ?? problemMessage(error));

      if (options.fields && options.setFieldError) {
        applyFieldErrors(error, options.fields, options.setFieldError);
      }

      return errorCode;
    },
    []
  );

  return {
    /** Câu hiển thị trên banner, `null` khi chưa có lỗi. */
    message,
    /** Mã lỗi ổn định, dùng để rẽ nhánh điều hướng ở phía caller. */
    code,
    traceId,
    /** Số giây còn phải chờ trước khi được submit lại. */
    cooldown,
    isCoolingDown: cooldown > 0,
    capture,
    reset,
  };
}
