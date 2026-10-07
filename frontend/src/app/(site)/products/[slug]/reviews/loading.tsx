import { Skeleton } from "@/components/ui/skeleton";
export default function Loading() { return <main aria-busy aria-label="Đang tải đánh giá" className="mx-auto w-full max-w-small space-y-8 px-6 py-12 md:px-8"><Skeleton className="h-12 w-2/3" /><Skeleton className="h-24 w-full" />{[0,1,2].map((i) => <Skeleton key={i} className="h-32 w-full" />)}</main>; }
