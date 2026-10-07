"use client";

import { useId, useRef, useState } from "react";
import { ImagePlus, LoaderCircle, RotateCw, TriangleAlert, X } from "lucide-react";

import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import type { MediaItem } from "@/hooks/use-review-media-uploader";
import { REVIEW_MEDIA_ACCEPT, REVIEW_MEDIA_RULES } from "@/lib/review-media";

interface MediaUploaderProps {
  items: MediaItem[];
  onAdd: (files: File[]) => string[];
  onRemove: (key: string) => void;
  onRetry: (key: string) => void;
  disabled?: boolean;
}

/** Chọn, xem trước, theo dõi tiến độ và gỡ ảnh/video đính kèm của một đánh giá. */
export function MediaUploader({ items, onAdd, onRemove, onRetry, disabled }: MediaUploaderProps) {
  const inputId = useId();
  const input = useRef<HTMLInputElement>(null);
  const [rejections, setRejections] = useState<string[]>([]);
  const full = items.length >= REVIEW_MEDIA_RULES.maxFiles;

  function pick(files: FileList | null) {
    if (!files?.length) return;
    setRejections(onAdd([...files]));
    // Cho phép chọn lại đúng tệp vừa gỡ.
    if (input.current) input.current.value = "";
  }

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-3">
        <input
          ref={input}
          id={inputId}
          type="file"
          multiple
          accept={REVIEW_MEDIA_ACCEPT}
          className="sr-only"
          disabled={disabled || full}
          onChange={(event) => pick(event.target.files)}
        />
        <Button type="button" variant="outline" disabled={disabled || full} onClick={() => input.current?.click()}>
          <ImagePlus aria-hidden />
          Thêm ảnh hoặc video
        </Button>
        <p className="text-caption text-muted-foreground">
          {items.length}/{REVIEW_MEDIA_RULES.maxFiles} tệp · Ảnh JPEG, PNG, WebP tới 10 MB · Video MP4, WebM tới 100 MB
        </p>
      </div>

      {rejections.length > 0 ? (
        <Alert variant="warning" title="Một số tệp chưa được thêm">
          <ul className="list-disc space-y-1 pl-5">
            {rejections.map((reason) => <li key={reason}>{reason}</li>)}
          </ul>
        </Alert>
      ) : null}

      {items.length > 0 ? (
        <ul className="grid grid-cols-2 gap-3 sm:grid-cols-3" aria-label="Tệp đính kèm">
          {items.map((item) => (
            <li key={item.key} className="space-y-2">
              <div className="relative aspect-square overflow-hidden rounded-xl border border-border bg-surface-2">
                {item.mediaType === "VIDEO" ? (
                  <video src={item.previewUrl} muted preload="metadata" aria-label={`Xem trước video ${item.file.name}`} className="size-full object-cover" />
                ) : (
                  // eslint-disable-next-line @next/next/no-img-element -- object URL cục bộ, không qua image optimizer.
                  <img src={item.previewUrl} alt={`Xem trước ảnh ${item.file.name}`} className="size-full object-cover" />
                )}

                {item.status === "uploading" ? (
                  <div className="absolute inset-0 grid place-content-center gap-2 bg-background/70 text-center text-caption">
                    <LoaderCircle className="mx-auto size-5 animate-spin" aria-hidden />
                    <span>Đang tải lên {Math.round(item.progress * 100)}%</span>
                  </div>
                ) : null}
                {item.status === "error" ? (
                  <div className="absolute inset-0 grid place-content-center bg-danger-surface/90 p-2 text-center text-danger">
                    <TriangleAlert className="mx-auto size-5" aria-hidden />
                  </div>
                ) : null}

                <button
                  type="button"
                  onClick={() => onRemove(item.key)}
                  disabled={disabled}
                  aria-label={`Gỡ tệp ${item.file.name}`}
                  className="focus-ring absolute right-1 top-1 grid size-11 place-content-center rounded-lg bg-background/85 text-foreground hover:bg-background disabled:opacity-60"
                >
                  <X className="size-4" aria-hidden />
                </button>
              </div>

              {item.status === "error" ? (
                <div className="space-y-1" role="alert">
                  <p className="text-caption text-danger">{item.error}</p>
                  <Button type="button" variant="ghost" size="sm" onClick={() => onRetry(item.key)} disabled={disabled}>
                    <RotateCw aria-hidden />
                    Thử lại
                  </Button>
                </div>
              ) : (
                <p className="truncate text-caption text-muted-foreground" title={item.file.name}>{item.file.name}</p>
              )}
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}
