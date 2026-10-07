import { mergeProps } from "@base-ui/react/merge-props"
import { useRender } from "@base-ui/react/use-render"
import { cva, type VariantProps } from "class-variance-authority"

import { cn } from "@/lib/utils"

/**
 * Terra badge — là nhãn, không phải nút. Không dùng nền đặc màu mạnh; các variant
 * trạng thái dùng bộ ba surface / text / border của token.
 */
const badgeVariants = cva(
  "group/badge inline-flex w-fit shrink-0 items-center justify-center gap-1 overflow-hidden rounded-md border border-transparent px-2 py-0.5 text-caption font-medium whitespace-nowrap transition-colors duration-200 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring [&>svg]:pointer-events-none [&>svg]:size-3!",
  {
    variants: {
      variant: {
        default: "bg-surface-3 text-foreground-secondary",
        clay: "border-clay/25 bg-clay/12 text-clay-strong",
        secondary: "bg-secondary text-secondary-foreground",
        success:
          "border-success-border bg-success-surface text-success",
        warning:
          "border-warning-border bg-warning-surface text-warning",
        destructive:
          "border-danger-border bg-danger-surface text-danger",
        info: "border-info-border bg-info-surface text-info",
        outline: "border-border-strong text-foreground-secondary",
        ghost: "text-muted-foreground hover:bg-accent hover:text-foreground",
        link: "text-clay-strong underline-offset-4 hover:underline",
      },
    },
    defaultVariants: {
      variant: "default",
    },
  }
)

function Badge({
  className,
  variant = "default",
  render,
  ...props
}: useRender.ComponentProps<"span"> & VariantProps<typeof badgeVariants>) {
  return useRender({
    defaultTagName: "span",
    props: mergeProps<"span">(
      {
        className: cn(badgeVariants({ variant }), className),
      },
      props
    ),
    render,
    state: {
      slot: "badge",
      variant,
    },
  })
}

export { Badge, badgeVariants }
