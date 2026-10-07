"use client"

import { Checkbox as CheckboxPrimitive } from "@base-ui/react/checkbox"

import { cn } from "@/lib/utils"
import { CheckIcon } from "lucide-react"

function Checkbox({ className, ...props }: CheckboxPrimitive.Root.Props) {
  return (
    <CheckboxPrimitive.Root
      data-slot="checkbox"
      className={cn(
        [
          "peer relative flex size-4 shrink-0 items-center justify-center rounded-sm",
          "border border-border-strong bg-surface-overlay",
          "transition-colors duration-150 ease-out-soft",
          // Vùng bấm nới rộng ra ngoài ô 16px.
          "after:absolute after:-inset-x-3 after:-inset-y-2",
          "outline-none focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring",
          "disabled:cursor-not-allowed disabled:opacity-50 group-has-disabled/field:opacity-50",
          "aria-invalid:border-danger",
          "data-checked:border-primary data-checked:bg-primary data-checked:text-primary-foreground",
        ].join(" "),
        className
      )}
      {...props}
    >
      <CheckboxPrimitive.Indicator
        data-slot="checkbox-indicator"
        className="grid place-content-center text-current [&>svg]:size-3.5"
      >
        <CheckIcon strokeWidth={3} />
      </CheckboxPrimitive.Indicator>
    </CheckboxPrimitive.Root>
  )
}

export { Checkbox }
