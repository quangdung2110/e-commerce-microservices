"use client";

import { useSearchStore } from "@/store/useSearchStore";
import { Checkbox } from "@/components/ui/checkbox";
import { Slider } from "@/components/ui/slider";
import { Switch } from "@/components/ui/switch";
import { Label } from "@/components/ui/label";
import { Accordion, AccordionContent, AccordionItem, AccordionTrigger } from "@/components/ui/accordion";

export function FilterSidebar() {
  const { 
    priceMin, priceMax, setPriceRange, 
    inStock, hasPromotion, setStockPromotion,
    filters, toggleFilter, clearFilters,
    facets
  } = useSearchStore();

  return (
    <aside className="w-full space-y-6 sticky top-28">
      <div className="flex items-center justify-between">
        <h2 className="text-lg font-black text-white uppercase tracking-widest font-mono">Bộ lọc tìm kiếm</h2>
        <button onClick={clearFilters} className="text-xs font-bold text-forest-bright hover:underline uppercase tracking-wide font-mono">
          Xóa tất cả
        </button>
      </div>

      {/* Switches */}
      <div className="space-y-4 bg-[#0c0c0f] border border-white/10 p-5 rounded-2xl shadow-[0_0_0_1px_rgba(255,255,255,0.04)_inset] relative overflow-hidden group">
        <div className="absolute inset-0 bg-[radial-gradient(ellipse_at_top,_var(--tw-gradient-stops))] from-forest/5 via-transparent to-transparent opacity-0 group-hover:opacity-100 transition-opacity duration-500" />
        <div className="flex items-center justify-between relative z-10">
          <Label htmlFor="in-stock" className="text-sm font-medium cursor-pointer text-white/90">Chỉ hiển thị còn hàng</Label>
          <Switch id="in-stock" checked={inStock} onCheckedChange={(c) => setStockPromotion(c, hasPromotion)} />
        </div>
        <div className="flex items-center justify-between relative z-10">
          <Label htmlFor="promo" className="text-sm font-medium cursor-pointer text-white/90">Đang khuyến mãi</Label>
          <Switch id="promo" checked={hasPromotion} onCheckedChange={(c) => setStockPromotion(inStock, c)} />
        </div>
      </div>

      <Accordion className="w-full">
        {/* Price Range */}
        <AccordionItem value="price" className="border-white/10">
          <AccordionTrigger className="hover:no-underline font-bold text-sm text-white/90 font-mono tracking-wide uppercase">Mức giá</AccordionTrigger>
          <AccordionContent className="pt-4 pb-6 space-y-6">
            <Slider
              defaultValue={[0, 50000000]}
              max={50000000}
              step={1000000}
              className="w-full"
              onValueChange={(val) => { const v = val as number[]; setPriceRange(v[0], v[1]); }}
            />
            <div className="flex items-center justify-between gap-4">
              <div className="flex-1 bg-[#1a2e22] border border-forest/30 rounded-lg px-3 py-2 text-xs font-mono font-bold text-forest-bright text-center shadow-[0_0_10px_rgba(40,200,64,0.1)_inset]">
                {priceMin ? new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND' }).format(priceMin) : "0đ"}
              </div>
              <span className="text-white/30 font-mono">-</span>
              <div className="flex-1 bg-[#1a2e22] border border-forest/30 rounded-lg px-3 py-2 text-xs font-mono font-bold text-forest-bright text-center shadow-[0_0_10px_rgba(40,200,64,0.1)_inset]">
                {priceMax ? new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND' }).format(priceMax) : "50tr+"}
              </div>
            </div>
          </AccordionContent>
        </AccordionItem>

        {/* Dynamic Facets (Brands, Categories, etc.) */}
        {facets?.filters?.map((group: any, i: number) => {
          if (group.type !== "CHECKBOX") return null;
          
          return (
            <AccordionItem key={`facet-${i}`} value={`facet-${i}`} className="border-white/10">
              <AccordionTrigger className="hover:no-underline font-bold text-sm text-white/90 font-mono tracking-wide uppercase">{group.name === 'categories' ? 'Danh mục' : group.name}</AccordionTrigger>
              <AccordionContent className="pt-2 pb-4 space-y-3">
                {group.options.map((opt: any) => {
                  const filterKey = group.name === 'categories' ? 'category' : group.name;
                  return (
                    <div key={opt.value} className="flex items-center justify-between group/opt">
                      <div className="flex items-center space-x-3">
                        <Checkbox 
                          id={`filter-${filterKey}-${opt.value}`} 
                          checked={(filters[filterKey] || []).includes(opt.value)}
                          onCheckedChange={() => toggleFilter(filterKey, opt.value)}
                          className="border-white/20 data-[state=checked]:bg-forest-bright data-[state=checked]:text-black data-[state=checked]:border-forest-bright"
                        />
                        <Label 
                          htmlFor={`filter-${filterKey}-${opt.value}`}
                          className="text-sm font-medium leading-none cursor-pointer group-hover/opt:text-forest-bright text-white/70 transition-colors"
                        >
                          {opt.label}
                        </Label>
                      </div>
                      <span className="text-xs font-mono text-white/40 group-hover/opt:text-forest-bright transition-colors">
                        {opt.count}
                      </span>
                    </div>
                  );
                })}
              </AccordionContent>
            </AccordionItem>
          );
        })}
      </Accordion>
    </aside>
  );
}
