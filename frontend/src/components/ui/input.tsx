import * as React from "react"
import { Input as InputPrimitive } from "@base-ui/react/input"

import { cn } from "@/lib/utils"

/**
 * Terra input.
 *
 * Ô nhập có bề mặt riêng (`surface-overlay`) nổi hơn nền trang — đó là cách người
 * dùng nhận ra nó nhập được. Không dùng input nền trong suốt.
 */
function Input({ className, type, ...props }: React.ComponentProps<"input">) {
  return (
    <InputPrimitive
      type={type}
      data-slot="input"
      className={cn(
        [
          "h-11 w-full min-w-0 rounded-lg px-3.5 text-[15px] text-foreground",
          "border border-border-strong bg-surface-overlay",
          "transition-[border-color] duration-200 ease-out-soft",
          "placeholder:text-muted-foreground",
          "hover:border-sand-400 dark:hover:border-sand-600",
          "outline-none focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring",
          "focus-visible:border-transparent",
          "disabled:pointer-events-none disabled:cursor-not-allowed disabled:bg-surface-3 disabled:text-muted-foreground",
          "aria-invalid:border-danger aria-invalid:outline-danger",
          "file:inline-flex file:h-7 file:border-0 file:bg-transparent file:text-[14px] file:font-medium file:text-foreground",
        ].join(" "),
        className
      )}
      {...props}
    />
  )
}

export { Input }
