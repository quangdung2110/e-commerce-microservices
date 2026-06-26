"use client";

import { useSearchStore } from "@/store/useSearchStore";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";

export function SortDropdown() {
  const { sort, setSort } = useSearchStore();

  return (
    <div className="flex items-center gap-3">
      <span className="text-xs font-bold text-white/50 whitespace-nowrap uppercase tracking-widest font-mono">
        Sắp xếp theo:
      </span>
      <Select value={sort} onValueChange={(val) => val && setSort(val)}>
        <SelectTrigger className="w-[180px] bg-[#0c0c0f] border-white/10 shadow-[0_0_0_1px_rgba(255,255,255,0.04)_inset] rounded-xl focus:ring-forest-bright/20 text-white font-mono text-sm tracking-wide">
          <SelectValue placeholder="Chọn tiêu chí" />
        </SelectTrigger>
        <SelectContent className="rounded-xl border-white/10 shadow-2xl bg-[#0c0c0f] text-white">
          <SelectItem value="relevance" className="rounded-lg font-mono text-xs hover:bg-white/5 focus:bg-white/5 focus:text-forest-bright">Độ liên quan</SelectItem>
          <SelectItem value="price_asc" className="rounded-lg font-mono text-xs hover:bg-white/5 focus:bg-white/5 focus:text-forest-bright">Giá: Thấp đến Cao</SelectItem>
          <SelectItem value="price_desc" className="rounded-lg font-mono text-xs hover:bg-white/5 focus:bg-white/5 focus:text-forest-bright">Giá: Cao đến Thấp</SelectItem>
          <SelectItem value="newest" className="rounded-lg font-mono text-xs hover:bg-white/5 focus:bg-white/5 focus:text-forest-bright">Mới nhất</SelectItem>
          <SelectItem value="rating" className="rounded-lg font-mono text-xs hover:bg-white/5 focus:bg-white/5 focus:text-forest-bright">Đánh giá cao</SelectItem>
        </SelectContent>
      </Select>
    </div>
  );
}
