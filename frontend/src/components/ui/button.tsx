import { Button as ButtonPrimitive } from "@base-ui/react/button"
import { cva, type VariantProps } from "class-variance-authority"

import { cn } from "@/lib/utils"

/**
 * Terra button.
 *
 * Thứ bậc: `default` (mực đặc) cho hành động chính của một form, `clay` (đất cam)
 * cho hành động thương mại có tính chuyển đổi. Không đặt hai variant đặc này cạnh
 * nhau — cặp đúng là default + secondary, hoặc clay + outline.
 */
const buttonVariants = cva(
  [
    "group/button inline-flex shrink-0 items-center justify-center gap-2",
    "rounded-lg border border-transparent bg-clip-padding font-medium whitespace-nowrap select-none",
    "transition-[color,background-color,border-color,transform] duration-100 ease-out-soft",
    // Focus ring dùng outline chứ không dùng box-shadow, để không bị cắt bởi overflow.
    "outline-none focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring",
    // Phản hồi vật lý, nhỏ tới mức gần như không nhận ra.
    "active:scale-[.98]",
    "disabled:pointer-events-none disabled:opacity-50",
    "aria-invalid:border-danger",
    "[&_svg]:pointer-events-none [&_svg]:shrink-0 [&_svg:not([class*='size-'])]:size-4",
  ].join(" "),
  {
    variants: {
      variant: {
        default:
          "bg-primary text-primary-foreground hover:bg-sand-800 dark:hover:bg-sand-150",
        clay: "bg-clay text-on-accent hover:bg-clay-press",
        secondary:
          "bg-secondary text-secondary-foreground hover:bg-sand-250 hover:text-foreground dark:hover:bg-sand-700",
        outline:
          "border-border-strong bg-transparent text-foreground hover:bg-surface-2 aria-expanded:bg-surface-2",
        ghost:
          "text-foreground-secondary hover:bg-accent hover:text-foreground aria-expanded:bg-accent aria-expanded:text-foreground",
        destructive:
          "border-danger-border bg-danger-surface text-danger hover:border-danger",
        link: "text-clay-strong underline-offset-4 hover:underline",
      },
      size: {
        // 44px — chiều cao control chuẩn, cũng là ngưỡng touch target tối thiểu.
        default: "h-11 px-5 text-[15px]",
        xs: "h-8 gap-1.5 rounded-md px-2.5 text-[13px] [&_svg:not([class*='size-'])]:size-3.5",
        sm: "h-9 px-4 text-[14px]",
        lg: "h-12 px-6 text-[17px]",
        icon: "size-11",
        "icon-xs": "size-8 rounded-md [&_svg:not([class*='size-'])]:size-3.5",
        "icon-sm": "size-9",
        "icon-lg": "size-12",
      },
    },
    defaultVariants: {
      variant: "default",
      size: "default",
    },
  }
)

function Button({
  className,
  variant = "default",
  size = "default",
  ...props
}: ButtonPrimitive.Props & VariantProps<typeof buttonVariants>) {
  return (
    <ButtonPrimitive
      data-slot="button"
      className={cn(buttonVariants({ variant, size, className }))}
      {...props}
    />
  )
}

export { Button, buttonVariants }
