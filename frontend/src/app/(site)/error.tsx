"use client";
import { ErrorState } from "@/components/ui/error-state";
export default function ErrorPage({ error, unstable_retry }: { error: Error & { digest?: string }; unstable_retry: () => void }) {
  return <main className="mx-auto w-full max-w-small space-y-6 px-6 py-12"><h1 className="font-serif text-headline-2">Trang đang tạm gián đoạn</h1><ErrorState error={error} message="Chưa thể tải trang. Vui lòng thử lại." onRetry={unstable_retry} /></main>;
}
