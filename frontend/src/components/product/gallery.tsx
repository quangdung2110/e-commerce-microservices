"use client";

import { useState } from "react";
import { ChevronLeft, ChevronRight, ImageOff } from "lucide-react";
import { cn } from "@/lib/utils";
import { Skeleton } from "@/components/ui/skeleton";

// ─── Types ──────────────────────────────────────────────────────

interface GalleryImage {
  url: string;
  thumbnail_url?: string;
  media_type?: string;
}

interface GalleryProps {
  images: GalleryImage[];
  productName: string;
  isLoading?: boolean;
}

// ─── Loading Skeleton ───────────────────────────────────────────

export function GallerySkeleton() {
  return (
    <div className="space-y-4">
      <Skeleton className="aspect-square rounded-2xl bg-white/5 border border-white/10" />
      <div className="flex gap-3">
        {Array.from({ length: 4 }).map((_, i) => (
          <Skeleton key={i} className="h-20 w-20 rounded-xl bg-white/5 border border-white/10" />
        ))}
      </div>
    </div>
  );
}

// ─── Empty State ────────────────────────────────────────────────

function EmptyGallery() {
  return (
    <div className="aspect-square rounded-2xl bg-[#0c0c0f] border border-white/10 flex flex-col items-center justify-center gap-3 relative shadow-[0_0_50px_rgba(255,255,255,0.02)_inset]">
      <div className="absolute inset-0 bg-[radial-gradient(circle_at_center,_var(--tw-gradient-stops))] from-white/5 to-transparent rounded-2xl" />
      <ImageOff className="h-12 w-12 text-white/20 relative z-10" />
      <p className="text-sm font-bold text-white/50 uppercase tracking-widest font-mono relative z-10">Chưa có hình ảnh</p>
    </div>
  );
}

// ─── Component ──────────────────────────────────────────────────

export function Gallery({ images, productName, isLoading }: GalleryProps) {
  const [selectedIndex, setSelectedIndex] = useState(0);

  if (isLoading) return <GallerySkeleton />;
  if (!images || images.length === 0) return <EmptyGallery />;

  const currentImage = images[selectedIndex];

  const handlePrev = () => {
    setSelectedIndex((prev) => (prev > 0 ? prev - 1 : images.length - 1));
  };

  const handleNext = () => {
    setSelectedIndex((prev) => (prev < images.length - 1 ? prev + 1 : 0));
  };

  return (
    <div className="space-y-4">
      {/* Main Image */}
      <div className="relative aspect-square rounded-2xl bg-[#0c0c0f] border border-white/10 overflow-hidden group shadow-[0_0_50px_rgba(255,255,255,0.03)_inset]">
        <div className="absolute inset-0 bg-[radial-gradient(circle_at_center,_var(--tw-gradient-stops))] from-white/5 to-transparent opacity-0 group-hover:opacity-100 transition-opacity duration-500" />
        <img
          src={currentImage?.url}
          alt={`${productName} - Ảnh ${selectedIndex + 1}`}
          className="w-full h-full object-cover transition-opacity duration-300 relative z-10 mix-blend-screen"
          loading="eager"
        />

        {/* Navigation arrows (on hover) */}
        {images.length > 1 && (
          <>
            <button
              className="absolute left-3 top-1/2 -translate-y-1/2 h-10 w-10 rounded-full bg-black/80 backdrop-blur-md flex items-center justify-center opacity-0 group-hover:opacity-100 transition-opacity shadow-[0_0_15px_rgba(40,200,64,0.3)] hover:bg-black text-forest-bright border border-forest/30 z-20"
              onClick={handlePrev}
              aria-label="Ảnh trước"
            >
              <ChevronLeft className="h-5 w-5" />
            </button>
            <button
              className="absolute right-3 top-1/2 -translate-y-1/2 h-10 w-10 rounded-full bg-black/80 backdrop-blur-md flex items-center justify-center opacity-0 group-hover:opacity-100 transition-opacity shadow-[0_0_15px_rgba(40,200,64,0.3)] hover:bg-black text-forest-bright border border-forest/30 z-20"
              onClick={handleNext}
              aria-label="Ảnh sau"
            >
              <ChevronRight className="h-5 w-5" />
            </button>
          </>
        )}

        {/* Image counter */}
        {images.length > 1 && (
          <div className="absolute bottom-3 right-3 px-3 py-1 rounded-full bg-black/80 backdrop-blur-md text-xs font-bold text-forest-bright border border-forest/30 z-20 font-mono tracking-widest shadow-[0_0_10px_rgba(40,200,64,0.2)]">
            {selectedIndex + 1}/{images.length}
          </div>
        )}
      </div>

      {/* Thumbnail Strip */}
      {images.length > 1 && (
        <div className="flex gap-3 overflow-x-auto pb-2 scrollbar-none">
          {images.map((img, index) => (
            <button
              key={index}
              className={cn(
                "h-20 w-20 rounded-xl overflow-hidden border-2 flex-shrink-0 transition-all duration-300 relative bg-[#0c0c0f]",
                index === selectedIndex
                  ? "border-forest-bright shadow-[0_0_15px_rgba(40,200,64,0.4)] ring-2 ring-forest-bright/20 scale-105"
                  : "border-white/10 hover:border-white/30 opacity-50 hover:opacity-100"
              )}
              onClick={() => setSelectedIndex(index)}
              aria-label={`Xem ảnh ${index + 1}`}
            >
              <img
                src={img.thumbnail_url || img.url}
                alt={`${productName} thumbnail ${index + 1}`}
                className="w-full h-full object-cover mix-blend-screen"
                loading="lazy"
              />
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
