"use client";

import { useState } from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { ChevronRight, Menu, Store } from "lucide-react";

import { AdminSidebar } from "@/components/admin/admin-sidebar";
import { adminBreadcrumbLabel } from "@/components/admin/navigation";
import { AccountMenu } from "@/components/layout/account-menu";
import { ThemeToggle } from "@/components/layout/theme-toggle";
import { Button } from "@/components/ui/button";
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
  SheetTrigger,
} from "@/components/ui/sheet";

export function AdminShell({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const [drawerOpen, setDrawerOpen] = useState(false);

  const currentLabel = adminBreadcrumbLabel(pathname);

  return (
    <div className="min-h-svh bg-background text-foreground">
      <aside className="fixed inset-y-0 left-0 z-30 hidden w-60 border-r border-border bg-surface-2 lg:block">
        <AdminSidebar />
      </aside>

      <div className="min-h-svh lg:pl-60">
        <header className="sticky top-0 z-30 border-b border-border bg-background/95 backdrop-blur-sm">
          <div className="mx-auto flex h-16 w-full max-w-medium items-center gap-2 px-4 sm:px-6 md:px-8">
            <Sheet open={drawerOpen} onOpenChange={setDrawerOpen}>
              <SheetTrigger
                aria-label="Mở điều hướng quản trị"
                className="focus-ring grid size-11 place-content-center rounded-lg text-foreground-secondary transition-colors duration-200 hover:bg-accent hover:text-foreground lg:hidden"
              >
                <Menu className="size-5" aria-hidden />
              </SheetTrigger>
              <SheetContent className="p-0">
                <SheetHeader className="sr-only">
                  <SheetTitle>Điều hướng quản trị</SheetTitle>
                  <SheetDescription>Chọn khu vực quản trị cần mở.</SheetDescription>
                </SheetHeader>
                <AdminSidebar onNavigate={() => setDrawerOpen(false)} />
              </SheetContent>
            </Sheet>

            <nav aria-label="Đường dẫn" className="min-w-0 flex-1">
              <ol className="flex items-center gap-2 text-[14px]">
                <li className="hidden sm:block">
                  <Link
                    href="/admin"
                    className="focus-ring rounded-md text-muted-foreground hover:text-foreground"
                  >
                    Quản trị
                  </Link>
                </li>
                {pathname !== "/admin" ? (
                  <>
                    <li className="hidden sm:block" aria-hidden>
                      <ChevronRight className="size-4 text-muted-foreground" />
                    </li>
                    <li className="truncate font-medium" aria-current="page">
                      {currentLabel}
                    </li>
                  </>
                ) : (
                  <li className="truncate font-medium sm:hidden" aria-current="page">
                    {currentLabel}
                  </li>
                )}
              </ol>
            </nav>

            <Button variant="ghost" size="sm" nativeButton={false} render={<Link href="/" />} className="hidden sm:inline-flex">
              <Store aria-hidden />
              Về cửa hàng
            </Button>
            <ThemeToggle />
            <AccountMenu />
          </div>
        </header>

        <main className="mx-auto w-full max-w-medium px-6 py-10 md:px-8 md:py-12">
          {children}
        </main>
      </div>
    </div>
  );
}
