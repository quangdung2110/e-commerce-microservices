"use client";

import { useTheme } from "next-themes";
import { Moon, Sun } from "lucide-react";

import { Button } from "@/components/ui/button";
import { useIsClient } from "@/hooks/use-client-value";

export function ThemeToggle() {
  const { resolvedTheme, setTheme } = useTheme();
  // Theme thực tế chỉ biết được ở client; render icon trước đó sẽ gây lệch hydration.
  const mounted = useIsClient();

  // Trước khi hydrate xong, cả icon lẫn aria-label phải giống bản server render.
  const isDark = mounted && resolvedTheme === "dark";

  return (
    <Button
      variant="ghost"
      size="icon"
      aria-label={isDark ? "Chuyển sang giao diện sáng" : "Chuyển sang giao diện tối"}
      onClick={() => setTheme(isDark ? "light" : "dark")}
    >
      {mounted ? (
        isDark ? <Sun className="size-5" /> : <Moon className="size-5" />
      ) : (
        <span className="size-5" />
      )}
    </Button>
  );
}
