"use client";
import { useState } from "react";
import { ImageOff } from "lucide-react";

/** URL do backend trả, gồm signed media; không qua image optimizer/cache. */
export function StorefrontImage({ src, alt, className }: { src: string; alt: string; className?: string }) {
  const [failedSrc, setFailedSrc] = useState<string | null>(null);
  if (!src || failedSrc === src) return <div role="img" aria-label={alt} className="flex size-full min-h-24 items-center justify-center bg-surface-2 text-muted-foreground"><ImageOff className="size-8" aria-hidden /><span className="sr-only">Ảnh chưa khả dụng</span></div>;
  // eslint-disable-next-line @next/next/no-img-element -- backend URL, tránh cache signed URL Review.
  return <img src={src} alt={alt} loading="lazy" className={className} onError={() => setFailedSrc(src)} />;
}
