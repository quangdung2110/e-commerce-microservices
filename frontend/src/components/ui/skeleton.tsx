import { cn } from "@/lib/utils"

/**
 * Skeleton phải mô phỏng hình dạng thật của nội dung sắp thay thế nó — một khối
 * xám to tướng không phải skeleton.
 */
function Skeleton({ className, ...props }: React.ComponentProps<"div">) {
  return (
    <div
      data-slot="skeleton"
      aria-hidden
      className={cn("animate-pulse rounded-md bg-surface-3", className)}
      {...props}
    />
  )
}

export { Skeleton }
