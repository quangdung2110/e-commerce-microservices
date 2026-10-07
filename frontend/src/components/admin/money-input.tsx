"use client";

import { Input } from "@/components/ui/input";
import { cn } from "@/lib/utils";

const integerFormatter = new Intl.NumberFormat("vi-VN", { maximumFractionDigits: 0 });

interface MoneyInputProps
  extends Omit<React.ComponentProps<typeof Input>, "value" | "onChange" | "inputMode"> {
  value?: number;
  onValueChange?: (value: number | undefined) => void;
}

export function MoneyInput({
  value,
  onValueChange,
  className,
  ...props
}: MoneyInputProps) {
  return (
    <div className="relative">
      <Input
        {...props}
        value={value === undefined ? "" : integerFormatter.format(value)}
        inputMode="numeric"
        className={cn("pr-14 tabular-nums", className)}
        onChange={(event) => {
          const digits = event.target.value.replace(/\D/g, "");
          if (!digits) {
            onValueChange?.(undefined);
            return;
          }
          const next = Number.parseInt(digits, 10);
          if (!Number.isSafeInteger(next)) return;
          onValueChange?.(next);
        }}
      />
      <span className="pointer-events-none absolute right-3.5 top-1/2 -translate-y-1/2 text-caption text-muted-foreground">
        VND
      </span>
    </div>
  );
}
