"use client";

import { useState } from "react";
import { cn } from "@/lib/utils";
import { Skeleton } from "@/components/ui/skeleton";
import { Separator } from "@/components/ui/separator";

// ─── Types ──────────────────────────────────────────────────────

interface TabProps {
  description?: string;
  attributes?: Record<string, any> | null;
  isLoading?: boolean;
}

// ─── Loading Skeleton ───────────────────────────────────────────

function TabsSkeleton() {
  return (
    <div className="space-y-4">
      <div className="flex gap-4 border-b border-white/10 pb-2">
        {Array.from({ length: 2 }).map((_, i) => (
          <Skeleton key={i} className="h-8 w-24 rounded-lg bg-white/5" />
        ))}
      </div>
      <div className="space-y-2">
        <Skeleton className="h-4 w-full bg-white/5" />
        <Skeleton className="h-4 w-3/4 bg-white/5" />
        <Skeleton className="h-4 w-5/6 bg-white/5" />
      </div>
    </div>
  );
}

// ─── Component ──────────────────────────────────────────────────

export function DescriptionTabs({
  description,
  attributes,
  isLoading,
}: TabProps) {
  const [activeTab, setActiveTab] = useState<"description" | "specs">(
    "description"
  );

  if (isLoading) return <TabsSkeleton />;

  const tabs = [
    { key: "description" as const, label: "Mô tả sản phẩm" },
    { key: "specs" as const, label: "Thông số kỹ thuật" },
  ];

  return (
    <div>
      {/* Tab navigation */}
      <div className="flex gap-0 border-b border-white/10">
        {tabs.map((tab) => (
          <button
            key={tab.key}
            onClick={() => setActiveTab(tab.key)}
            className={cn(
              "px-6 py-3 text-xs font-bold transition-colors relative font-mono uppercase tracking-widest",
              activeTab === tab.key
                ? "text-forest-bright"
                : "text-white/50 hover:text-white"
            )}
          >
            {tab.label}
            {activeTab === tab.key && (
              <span className="absolute bottom-0 left-0 right-0 h-[2px] bg-forest-bright rounded-full shadow-[0_0_10px_rgba(40,200,64,0.5)]" />
            )}
          </button>
        ))}
      </div>

      {/* Tab content */}
      <div className="py-6">
        {activeTab === "description" && (
          <div className="prose prose-sm dark:prose-invert max-w-none">
            {description ? (
              <p className="text-sm text-white/70 leading-relaxed whitespace-pre-line font-mono">
                {description}
              </p>
            ) : (
              <p className="text-sm text-white/30 italic font-mono uppercase tracking-widest">
                Chưa có mô tả cho sản phẩm này.
              </p>
            )}
          </div>
        )}

        {activeTab === "specs" && (
          <div>
            {attributes && Object.keys(attributes).length > 0 ? (
              <div className="divide-y divide-white/10 rounded-xl border border-white/10 bg-[#0c0c0f] shadow-[0_0_0_1px_rgba(255,255,255,0.04)_inset]">
                {Object.entries(attributes).map(([key, value]) => (
                  <div
                    key={key}
                    className="flex items-center px-4 py-3 text-sm even:bg-white/5"
                  >
                    <span className="w-1/3 font-bold text-white/90 capitalize font-mono tracking-wide">
                      {key}
                    </span>
                    <span className="w-2/3 text-white/70 font-mono">
                      {typeof value === "object"
                        ? JSON.stringify(value)
                        : String(value)}
                    </span>
                  </div>
                ))}
              </div>
            ) : (
              <p className="text-sm text-white/30 italic font-mono uppercase tracking-widest">
                Chưa có thông số kỹ thuật cho sản phẩm này.
              </p>
            )}
          </div>
        )}
      </div>
    </div>
  );
}
