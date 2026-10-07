import Link from "next/link";
import { buttonVariants } from "@/components/ui/button";
export default function NotFound() { return <main className="mx-auto w-full max-w-small space-y-6 px-6 py-16 text-center"><h1 className="font-serif text-headline-2">Không tìm thấy trang này</h1><p className="text-muted-foreground">Sản phẩm hoặc danh mục có thể đã ngừng hiển thị.</p><Link href="/search?in_stock=false" className={buttonVariants({ variant: "outline" })}>Khám phá sản phẩm khác</Link></main>; }
