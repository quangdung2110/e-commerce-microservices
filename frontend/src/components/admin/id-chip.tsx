"use client";

import { useEffect, useState } from "react";
import { Check, Copy } from "lucide-react";

import { cn } from "@/lib/utils";

export function IdChip({
  value,
  label = "Sao chép mã",
  className,
}: {
  value: string;
  label?: string;
  className?: string;
}) {
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    if (!copied) return;
    const timeout = window.setTimeout(() => setCopied(false), 1500);
    return () => window.clearTimeout(timeout);
  }, [copied]);

  const short = value.length > 12 ? `${value.slice(0, 8)}…${value.slice(-4)}` : value;

  return (
    <button
      type="button"
      aria-label={`${label}: ${value}`}
      title={value}
      onClick={async () => {
        await navigator.clipboard.writeText(value);
        setCopied(true);
      }}
      className={cn(
        "focus-ring inline-flex min-h-9 items-center gap-1.5 rounded-md border border-border bg-surface-2 px-2.5 font-mono text-caption text-foreground-secondary transition-colors duration-200 hover:border-border-strong hover:text-foreground",
        className
      )}
    >
      <span>{short}</span>
      {copied ? (
        <Check className="size-3.5 text-success" aria-hidden />
      ) : (
        <Copy className="size-3.5" aria-hidden />
      )}
      <span className="sr-only" aria-live="polite">
        {copied ? "Đã sao chép" : ""}
      </span>
    </button>
  );
}
