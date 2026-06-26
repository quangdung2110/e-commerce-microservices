"use client";

import { cn } from "@/lib/utils";
import { usePDPStore } from "@/store/usePDPStore";

// ─── Component ──────────────────────────────────────────────────

export function VariantPicker() {
  const variantOptions = usePDPStore((s) => s.variantOptions);
  const selectedAttributes = usePDPStore((s) => s.selectedAttributes);
  const setSelectedAttribute = usePDPStore((s) => s.setSelectedAttribute);
  const variants = usePDPStore((s) => s.variants);

  if (!variantOptions || variantOptions.length === 0) return null;

  return (
    <div className="space-y-4">
      {variantOptions.map((option) => (
        <div key={option.name}>
          {/* Label */}
          <p className="text-xs font-bold text-white/50 mb-2 uppercase tracking-widest font-mono">
            {option.label}:
            <span className="ml-1.5 font-bold text-forest-bright font-sans">
              {selectedAttributes[option.name] || ""}
            </span>
          </p>

          {/* Options */}
          <div className="flex flex-wrap gap-2">
            {option.values.map((value) => {
              const isSelected = selectedAttributes[option.name] === value;

              // Check if this combination is available (has a matching SKU)
              const isValid = variants.some((v) =>
                Object.entries({
                  ...selectedAttributes,
                  [option.name]: value,
                }).every(([k, v2]) => v.attributes[k] === v2)
              );

              return (
                <button
                  key={value}
                  onClick={() => setSelectedAttribute(option.name, value)}
                  disabled={!isValid}
                  className={cn(
                    "px-4 py-2 rounded-xl text-xs uppercase tracking-wider border transition-all duration-300 font-mono",
                    isSelected
                      ? "bg-forest/20 text-forest-bright font-black border-forest-bright shadow-[0_0_15px_rgba(40,200,64,0.4)] ring-1 ring-forest-bright"
                      : isValid
                      ? "bg-[#0c0c0f] text-white/80 border-white/20 hover:border-forest-bright hover:text-white hover:shadow-[0_0_10px_rgba(40,200,64,0.2)] font-bold"
                      : "bg-white/5 text-white/20 border-white/5 line-through cursor-not-allowed opacity-50"
                  )}
                >
                  {value}
                </button>
              );
            })}
          </div>
        </div>
      ))}
    </div>
  );
}
