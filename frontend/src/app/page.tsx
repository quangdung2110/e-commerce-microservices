import { FilterSidebar } from "@/components/catalog/filter-sidebar";
import { ProductGrid } from "@/components/catalog/product-grid";
import { SortDropdown } from "@/components/catalog/sort-dropdown";
import { ProductTotal } from "@/components/catalog/product-total";
import { ChevronRight, Home } from "lucide-react";

export default function HomeBrowser() {
  return (
    <div className="flex flex-col flex-1 relative z-10">
      {/* Main Content */}
      <main className="flex-1 container mx-auto px-4 py-8">
        {/* Breadcrumb */}
        <div className="flex items-center gap-2 text-sm text-white/50 font-mono mb-8">
          <Home className="h-4 w-4" />
          <ChevronRight className="h-3 w-3 opacity-50" />
          <span className="font-medium text-white/90">Sản phẩm công nghệ</span>
        </div>

        {/* Page Title & Sort Bar */}
        <div className="flex flex-col md:flex-row md:items-end justify-between gap-4 mb-8 pb-4 border-b border-white/10">
          <div>
            <h1 className="text-3xl md:text-4xl font-black tracking-tight text-white">
              Điện thoại & Phụ kiện
            </h1>
            <ProductTotal />
          </div>
          <div className="flex-shrink-0">
            <SortDropdown />
          </div>
        </div>

        {/* Catalog Layout */}
        <div className="flex flex-col lg:flex-row gap-10">
          {/* Sidebar */}
          <div className="w-full lg:w-64 flex-shrink-0">
            <FilterSidebar />
          </div>

          {/* Product Grid Area */}
          <div className="flex-1 min-w-0">
            <ProductGrid />
          </div>
        </div>
      </main>
    </div>
  );
}
