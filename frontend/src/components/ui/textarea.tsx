import * as React from "react";

import { cn } from "@/lib/utils";

function Textarea({ className, ...props }: React.ComponentProps<"textarea">) {
  return (
    <textarea
      data-slot="textarea"
      className={cn(
        [
          "min-h-28 w-full resize-y rounded-lg px-3.5 py-3 text-[15px] text-foreground",
          "border border-border-strong bg-surface-overlay",
          "transition-[border-color] duration-200 ease-out-soft",
          "placeholder:text-muted-foreground",
          "hover:border-sand-400 dark:hover:border-sand-600",
          "outline-none focus-visible:border-transparent focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring",
          "disabled:cursor-not-allowed disabled:bg-surface-3 disabled:text-muted-foreground",
          "aria-invalid:border-danger aria-invalid:outline-danger",
        ].join(" "),
        className
      )}
      {...props}
    />
  );
}

export { Textarea };
