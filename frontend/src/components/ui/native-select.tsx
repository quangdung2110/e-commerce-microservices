import * as React from "react";
import { ChevronDown } from "lucide-react";

import { cn } from "@/lib/utils";

/**
 * Select dựng trên `<select>` gốc của trình duyệt.
 *
 * Chọn giải pháp này thay vì listbox tự dựng cho các form dữ liệu (giới tính,
 * tỉnh/quận/phường) vì nó cho sẵn thứ mà bản tự dựng phải làm lại bằng tay và
 * thường làm sai: bàn phím, tìm kiếm theo ký tự, và bánh xe chọn gốc trên
 * thiết bị di động.
 */
function NativeSelect({
  className,
  children,
  ...props
}: React.ComponentProps<"select">) {
  return (
    <div className="relative">
      <select
        data-slot="native-select"
        className={cn(
          [
            "h-11 w-full appearance-none rounded-lg pl-3.5 pr-10 text-[15px] text-foreground",
            "border border-border-strong bg-surface-overlay",
            "transition-[border-color] duration-200 ease-out-soft",
            "hover:border-sand-400 dark:hover:border-sand-600",
            "outline-none focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring",
            "focus-visible:border-transparent",
            "disabled:cursor-not-allowed disabled:bg-surface-3 disabled:text-muted-foreground",
            "aria-invalid:border-danger aria-invalid:outline-danger",
          ].join(" "),
          className
        )}
        {...props}
      >
        {children}
      </select>
      <ChevronDown
        aria-hidden
        className="pointer-events-none absolute right-3.5 top-1/2 size-4 -translate-y-1/2 text-muted-foreground"
      />
    </div>
  );
}

export { NativeSelect };
