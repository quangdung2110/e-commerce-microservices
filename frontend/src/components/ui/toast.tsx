"use client";

import { createContext, useContext, useState, useCallback, type ReactNode } from "react";
import { X, CircleCheck, CircleAlert, Info, TriangleAlert } from "lucide-react";
import { cn } from "@/lib/utils";

// ─── Types ──────────────────────────────────────────────────────

type ToastType = "success" | "error" | "info" | "warning";

interface Toast {
  id: string;
  type: ToastType;
  title: string;
  message?: string;
  duration?: number;
}

interface ToastContextType {
  toasts: Toast[];
  addToast: (toast: Omit<Toast, "id">) => string;
  removeToast: (id: string) => void;
}

// ─── Context ────────────────────────────────────────────────────

const ToastContext = createContext<ToastContextType | null>(null);

export function useToast() {
  const ctx = useContext(ToastContext);
  if (!ctx) throw new Error("useToast must be used within ToastProvider");
  return ctx;
}

// ─── Provider ───────────────────────────────────────────────────

const ICONS: Record<ToastType, ReactNode> = {
  success: <CircleCheck className="size-5 text-success" />,
  error: <CircleAlert className="size-5 text-danger" />,
  warning: <TriangleAlert className="size-5 text-warning" />,
  info: <Info className="size-5 text-info" />,
};

const BORDERS: Record<ToastType, string> = {
  success: "border-success-border",
  error: "border-danger-border",
  warning: "border-warning-border",
  info: "border-info-border",
};

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);

  const addToast = useCallback((toast: Omit<Toast, "id">) => {
    const id = `toast-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`;
    setToasts((prev) => [...prev, { ...toast, id }]);

    // Auto-remove after duration
    const duration = toast.duration ?? 4000;
    if (duration > 0) {
      setTimeout(() => {
        setToasts((prev) => prev.filter((t) => t.id !== id));
      }, duration);
    }

    return id;
  }, []);

  const removeToast = useCallback((id: string) => {
    setToasts((prev) => prev.filter((t) => t.id !== id));
  }, []);

  return (
    <ToastContext.Provider value={{ toasts, addToast, removeToast }}>
      {children}

      {/* Toast container */}
      <div
        className="fixed right-4 bottom-[calc(var(--purchase-dock-height,0px)+1rem)] z-50 flex max-w-sm flex-col gap-2 lg:bottom-4"
        role="status"
        aria-live="polite"
      >
        {toasts.map((toast) => (
          <div
            key={toast.id}
            className={cn(
              "flex items-start gap-3 rounded-xl border bg-surface-overlay p-4 shadow-md animate-in fade-in slide-in-from-right-2 duration-200",
              BORDERS[toast.type]
            )}
          >
            {/* Icon */}
            <div className="flex-shrink-0 mt-0.5">
              {ICONS[toast.type]}
            </div>

            {/* Content */}
            <div className="min-w-0 flex-1">
              <p className="text-[14px] font-medium text-foreground">
                {toast.title}
              </p>
              {toast.message && (
                <p className="mt-0.5 text-caption text-muted-foreground">
                  {toast.message}
                </p>
              )}
            </div>

            {/* Close */}
            <button
              type="button"
              aria-label="Đóng thông báo"
              className="focus-ring shrink-0 rounded-sm text-muted-foreground transition-colors hover:text-foreground"
              onClick={() => removeToast(toast.id)}
            >
              <X className="size-4" />
            </button>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}
