"use client";

import { RotateCw } from "lucide-react";

import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { problemMessage, problemTraceId } from "@/lib/api/problem";

interface ErrorStateProps {
  /** Lỗi từ React Query / axios. Câu hiển thị được suy từ `code` và status. */
  error: unknown;
  /** Không truyền thì không có nút thử lại (ví dụ lỗi 403, thử lại vô ích). */
  onRetry?: () => void;
  /** Câu thay thế khi ngữ cảnh cần cụ thể hơn câu chung. */
  message?: string;
  className?: string;
}

/**
 * Trạng thái lỗi khi tải dữ liệu (Terra patterns §5): câu tiếng Việt, nút thử
 * lại, mã tra cứu nhỏ ở cuối. Không bao giờ hiển thị `detail` thô của backend.
 */
export function ErrorState({ error, onRetry, message, className }: ErrorStateProps) {
  return (
    <Alert variant="danger" traceId={problemTraceId(error)} className={className}>
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <span>{message ?? problemMessage(error)}</span>
        {onRetry && (
          <Button variant="outline" size="sm" onClick={onRetry} className="self-start sm:self-auto">
            <RotateCw aria-hidden />
            Thử lại
          </Button>
        )}
      </div>
    </Alert>
  );
}
