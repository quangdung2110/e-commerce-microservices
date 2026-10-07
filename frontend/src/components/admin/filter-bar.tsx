import { RotateCcw } from "lucide-react";

import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

interface FilterBarProps {
  children: React.ReactNode;
  hasFilters?: boolean;
  onReset?: () => void;
  className?: string;
}

export function FilterBar({
  children,
  hasFilters = false,
  onReset,
  className,
}: FilterBarProps) {
  return (
    <section
      aria-label="Bộ lọc"
      className={cn(
        "flex flex-col gap-4 rounded-xl border border-border bg-surface-2 p-4 md:flex-row md:items-end",
        className
      )}
    >
      <div className="grid min-w-0 flex-1 gap-4 sm:grid-cols-2">{children}</div>
      {onReset ? (
        <Button
          type="button"
          variant="ghost"
          size="sm"
          disabled={!hasFilters}
          onClick={onReset}
          className="self-start md:self-auto"
        >
          <RotateCcw aria-hidden />
          Xoá bộ lọc
        </Button>
      ) : null}
    </section>
  );
}
