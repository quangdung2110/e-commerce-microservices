import { ProductGridSkeleton } from "@/components/catalog/product-grid";
import { Skeleton } from "@/components/ui/skeleton";
export default function Loading() { return <main className="mx-auto w-full max-w-medium space-y-8 px-6 py-12 md:px-8"><Skeleton className="h-12 w-2/3" /><ProductGridSkeleton /></main>; }
