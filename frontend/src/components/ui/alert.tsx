import * as React from "react"
import { cva, type VariantProps } from "class-variance-authority"
import {
  CircleAlert,
  CircleCheck,
  Info,
  TriangleAlert,
  type LucideIcon,
} from "lucide-react"

import { cn } from "@/lib/utils"

/**
 * Banner thông báo cấp khối. Dùng cho lỗi của cả form (đặt trên đầu form) hoặc
 * ghi chú ngữ cảnh. Thông báo ngắn sau một hành động thì dùng toast, không dùng
 * Alert.
 */
const alertVariants = cva(
  "flex gap-3 rounded-lg border p-4 text-[14px] animate-in fade-in duration-200",
  {
    variants: {
      variant: {
        info: "border-info-border bg-info-surface text-info",
        success: "border-success-border bg-success-surface text-success",
        warning: "border-warning-border bg-warning-surface text-warning",
        danger: "border-danger-border bg-danger-surface text-danger",
      },
    },
    defaultVariants: {
      variant: "info",
    },
  }
)

const DEFAULT_ICON: Record<NonNullable<AlertVariant>, LucideIcon> = {
  info: Info,
  success: CircleCheck,
  warning: TriangleAlert,
  danger: CircleAlert,
}

type AlertVariant = VariantProps<typeof alertVariants>["variant"]

interface AlertProps
  // `title` ở đây là tiêu đề hiển thị (ReactNode), không phải tooltip HTML.
  extends Omit<React.ComponentProps<"div">, "title">,
    VariantProps<typeof alertVariants> {
  /** Bỏ icon khi Alert nằm trong ngữ cảnh đã quá rõ ràng. */
  icon?: LucideIcon | null
  title?: React.ReactNode
  /** Mã tra cứu của backend, hiển thị rất nhỏ ở cuối cho việc hỗ trợ. */
  traceId?: string
}

function Alert({
  className,
  variant = "info",
  icon,
  title,
  traceId,
  children,
  ...props
}: AlertProps) {
  const Icon = icon === null ? null : (icon ?? DEFAULT_ICON[variant ?? "info"])

  return (
    <div
      data-slot="alert"
      // Lỗi phải được đọc lên ngay khi xuất hiện; ghi chú thì không cần cắt ngang.
      role={variant === "danger" ? "alert" : "status"}
      aria-live={variant === "danger" ? "assertive" : "polite"}
      className={cn(alertVariants({ variant }), className)}
      {...props}
    >
      {Icon ? <Icon className="mt-0.5 size-4 shrink-0" aria-hidden /> : null}
      <div className="min-w-0 flex-1 space-y-1">
        {title ? <p className="font-medium">{title}</p> : null}
        {children ? <div className="text-[14px]">{children}</div> : null}
        {traceId ? (
          <p className="text-micro opacity-70">Mã tra cứu: {traceId}</p>
        ) : null}
      </div>
    </div>
  )
}

export { Alert, alertVariants }
