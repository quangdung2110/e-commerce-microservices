"use client";

import { useRef, useState, type KeyboardEvent } from "react";
import { Star } from "lucide-react";

import { RATING_LABELS } from "@/lib/labels/review";
import { cn } from "@/lib/utils";

/** Hiển thị điểm — chỉ đọc. Thông tin nằm ở `aria-label`, ngôi sao là trang trí. */
export function RatingStars({ value, className }: { value: number; className?: string }) {
  return (
    <span role="img" aria-label={`${value} trên 5 sao`} className={cn("inline-flex items-center gap-0.5", className)}>
      {[1, 2, 3, 4, 5].map((n) => (
        <Star
          key={n}
          aria-hidden
          className={cn("size-4", n <= value ? "fill-foreground text-foreground" : "text-border-strong")}
        />
      ))}
    </span>
  );
}

interface RatingInputProps {
  /** 0 = chưa chọn. */
  value: number;
  onChange: (value: number) => void;
  disabled?: boolean;
  invalid?: boolean;
  "aria-labelledby"?: string;
  "aria-describedby"?: string;
}

/**
 * Chọn 1–5 sao dạng radio group: một điểm dừng Tab, phím mũi tên đổi giá trị, mỗi nút
 * đủ 44px. Nhãn chữ bên cạnh để màu/hình không là kênh thông tin duy nhất.
 */
export function RatingInput({ value, onChange, disabled, invalid, ...aria }: RatingInputProps) {
  const [hover, setHover] = useState(0);
  const buttons = useRef<Array<HTMLButtonElement | null>>([]);
  const shown = hover || value;

  function select(next: number) {
    const clamped = Math.min(5, Math.max(1, next));
    onChange(clamped);
    buttons.current[clamped - 1]?.focus();
  }

  function onKeyDown(event: KeyboardEvent<HTMLButtonElement>, n: number) {
    switch (event.key) {
      case "ArrowRight":
      case "ArrowUp":
        event.preventDefault();
        select(n + 1);
        break;
      case "ArrowLeft":
      case "ArrowDown":
        event.preventDefault();
        select(n - 1);
        break;
      case "Home":
        event.preventDefault();
        select(1);
        break;
      case "End":
        event.preventDefault();
        select(5);
        break;
    }
  }

  return (
    <div className="flex flex-wrap items-center gap-x-4 gap-y-1">
      <div role="radiogroup" aria-invalid={invalid || undefined} {...aria} className="flex" onMouseLeave={() => setHover(0)}>
        {[1, 2, 3, 4, 5].map((n) => (
          <button
            key={n}
            ref={(element) => {
              buttons.current[n - 1] = element;
            }}
            type="button"
            role="radio"
            aria-checked={value === n}
            aria-label={`${n} sao, ${RATING_LABELS[n]}`}
            tabIndex={value === n || (value === 0 && n === 1) ? 0 : -1}
            disabled={disabled}
            onClick={() => onChange(n)}
            onKeyDown={(event) => onKeyDown(event, n)}
            onMouseEnter={() => setHover(n)}
            className="focus-ring grid size-11 place-content-center rounded-lg disabled:cursor-not-allowed disabled:opacity-60"
          >
            <Star
              aria-hidden
              className={cn(
                "size-7 transition-colors duration-150",
                n <= shown ? "fill-foreground text-foreground" : "text-border-strong"
              )}
            />
          </button>
        ))}
      </div>
      <span className="text-[14px] text-muted-foreground" aria-live="polite">
        {value ? `${value}/5 · ${RATING_LABELS[value]}` : "Chọn số sao"}
      </span>
    </div>
  );
}
