"use client";

import { useEffect, useRef, type ReactNode } from "react";

/** One action instance: fixed on mobile, in the normal flow on desktop. */
export function MobileActionDock({ label, amount, children, feedback }: {
  label: string; amount: string; children: ReactNode; feedback?: ReactNode;
}) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const element = ref.current;
    if (!element) return;
    const update = () => document.documentElement.style.setProperty("--purchase-dock-height", `${element.offsetHeight}px`);
    update();
    const observer = typeof ResizeObserver === "undefined" ? null : new ResizeObserver(update);
    observer?.observe(element);
    return () => { observer?.disconnect(); document.documentElement.style.removeProperty("--purchase-dock-height"); };
  }, []);

  return <div ref={ref} data-purchase-dock className="fixed inset-x-0 bottom-0 z-30 border-t border-border bg-surface-overlay px-4 pt-3 pb-[max(0.75rem,env(safe-area-inset-bottom))] lg:static lg:border-0 lg:bg-transparent lg:p-0">
    <div className="mx-auto flex max-w-medium items-center gap-3 lg:block">
      <div className="min-w-0 flex-1 lg:hidden"><p className="text-caption text-muted-foreground">{label}</p><p className="break-words text-[15px] font-medium tabular-nums" aria-live="polite">{amount}</p></div>
      <div className="min-w-0 flex-1 lg:w-full">{children}</div>
    </div>
    {feedback && <div className="mx-auto mt-3 max-h-[35svh] max-w-medium space-y-3 overflow-y-auto lg:max-h-none lg:overflow-visible">{feedback}</div>}
  </div>;
}
