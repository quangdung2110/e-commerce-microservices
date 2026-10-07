import Link from "next/link";

import { cn } from "@/lib/utils";

/**
 * Dấu hiệu nhận diện: một khối mực đặc với chữ T serif, đặt cạnh wordmark sans.
 * Chính sự tương phản serif/sans đó là chữ ký của hệ thống Terra.
 */
export function Logo({
  className,
  href = "/",
  showWordmark = true,
}: {
  className?: string;
  href?: string;
  showWordmark?: boolean;
}) {
  return (
    <Link
      href={href}
      aria-label="TechNova — về trang chủ"
      className={cn(
        "focus-ring group inline-flex shrink-0 items-center gap-2.5 rounded-lg",
        className
      )}
    >
      <span
        aria-hidden
        className="grid size-9 place-content-center rounded-lg bg-primary font-serif text-[19px] leading-none text-primary-foreground transition-colors duration-200 group-hover:bg-clay group-hover:text-on-accent"
      >
        T
      </span>
      {showWordmark && (
        <span className="text-[19px] font-medium tracking-tight text-foreground">
          TechNova
        </span>
      )}
    </Link>
  );
}
