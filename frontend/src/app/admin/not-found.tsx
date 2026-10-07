import Link from "next/link";
import { MapPinOff } from "lucide-react";

import { buttonVariants } from "@/components/ui/button";

export default function AdminNotFound() {
  return (
    <div className="flex min-h-[55svh] flex-col items-center justify-center gap-4 text-center">
      <MapPinOff className="size-8 text-muted-foreground" aria-hidden />
      <h1 className="text-headline-3 font-serif font-normal">Không tìm thấy trang quản trị</h1>
      <p className="max-w-prose-narrow text-[15px] text-muted-foreground">
        Đường dẫn này chưa tồn tại hoặc đã được chuyển sang khu vực khác.
      </p>
      <Link href="/admin" className={buttonVariants({ variant: "outline" })}>
        Về tổng quan
      </Link>
    </div>
  );
}
