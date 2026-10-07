"use client";
import { useRouter } from "next/navigation";
import { ErrorState } from "@/components/ui/error-state";
import type { PublicApiFailure } from "@/lib/api/storefront-server";
export function PublicError({ failure, message }: { failure: PublicApiFailure; message?: string }) {
  const router = useRouter();
  return <ErrorState error={failure} message={message} onRetry={() => router.refresh()} />;
}
