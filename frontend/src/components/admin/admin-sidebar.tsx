"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";

import { Logo } from "@/components/layout/logo";
import { ENABLED_ADMIN_NAV, currentAdminNavHref } from "@/components/admin/navigation";
import { cn } from "@/lib/utils";

export function AdminSidebar({ onNavigate }: { onNavigate?: () => void }) {
  const pathname = usePathname();
  const currentHref = currentAdminNavHref(pathname);

  return (
    <div className="flex min-h-full flex-col">
      <div className="border-b border-border px-5 py-5">
        <Logo href="/admin" />
        <p className="mt-2 pl-[46px] text-micro font-medium uppercase tracking-[0.16em] text-muted-foreground">
          Quản trị
        </p>
      </div>

      <nav aria-label="Điều hướng quản trị" className="flex-1 space-y-6 px-3 py-6">
        {ENABLED_ADMIN_NAV.map((group, groupIndex) => (
          <div key={group.label ?? groupIndex} className="space-y-1">
            {group.label ? (
              <p className="px-3 pb-1 text-micro font-semibold uppercase tracking-[0.14em] text-muted-foreground">
                {group.label}
              </p>
            ) : null}
            {group.items.map(({ href, label, icon: Icon }) => {
              const active = currentHref === href;
              return (
                <Link
                  key={href}
                  href={href}
                  aria-current={active ? "page" : undefined}
                  onClick={onNavigate}
                  className={cn(
                    "focus-ring relative flex min-h-11 items-center gap-3 rounded-lg px-3 text-[14px] text-foreground-secondary transition-colors duration-200 hover:bg-accent hover:text-foreground",
                    active && "bg-accent font-medium text-foreground"
                  )}
                >
                  {active ? (
                    <span
                      aria-hidden
                      className="absolute inset-y-2 left-0 w-0.5 rounded-full bg-clay"
                    />
                  ) : null}
                  <Icon className="size-4" aria-hidden />
                  {label}
                </Link>
              );
            })}
          </div>
        ))}
      </nav>

      <div className="border-t border-border px-5 py-4 text-caption text-muted-foreground">
        TechNova Admin
      </div>
    </div>
  );
}
