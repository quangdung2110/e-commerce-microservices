"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { cn } from "@/lib/utils";
import { User, MapPin, Settings, ShieldAlert } from "lucide-react";

const sidebarNavItems = [
  {
    title: "Hồ sơ cá nhân",
    href: "/profile",
    icon: User,
  },
  {
    title: "Sổ địa chỉ",
    href: "/profile/addresses",
    icon: MapPin,
  },
  {
    title: "Cài đặt & Thông báo",
    href: "/profile/preferences",
    icon: Settings,
  },
];

export function ProfileSidebar() {
  const pathname = usePathname();

  return (
    <nav className="flex space-x-2 lg:flex-col lg:space-x-0 lg:space-y-1">
      {sidebarNavItems.map((item) => {
        const isActive = pathname === item.href;
        return (
          <Link
            key={item.href}
            href={item.href}
            className={cn(
              "group flex items-center gap-x-3 rounded-md px-3 py-2 text-sm font-medium transition-all duration-300",
              isActive
                ? "bg-zinc-800/50 text-emerald-400 border border-zinc-700/50 shadow-[0_0_15px_rgba(16,185,129,0.1)]"
                : "text-zinc-400 hover:bg-zinc-800/30 hover:text-zinc-200"
            )}
          >
            <item.icon
              className={cn(
                "h-5 w-5 flex-shrink-0 transition-colors",
                isActive ? "text-emerald-400" : "text-zinc-500 group-hover:text-zinc-300"
              )}
              aria-hidden="true"
            />
            {item.title}
          </Link>
        );
      })}
    </nav>
  );
}
