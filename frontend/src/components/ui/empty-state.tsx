import type { LucideIcon } from "lucide-react";

import { cn } from "@/lib/utils";

interface EmptyStateProps {
  icon: LucideIcon;
  title: string;
  description: string;
  action?: React.ReactNode;
  className?: string;
}

export function EmptyState({
  icon: Icon,
  title,
  description,
  action,
  className,
}: EmptyStateProps) {
  return (
    <div
      className={cn(
        "flex flex-col items-center gap-3 rounded-xl border border-dashed border-border bg-surface-2 px-6 py-12 text-center",
        className
      )}
    >
      <Icon className="size-8 text-muted-foreground" aria-hidden />
      <p className="text-[15px] font-medium">{title}</p>
      <p className="max-w-prose-narrow text-caption text-muted-foreground">
        {description}
      </p>
      {action}
    </div>
  );
}
