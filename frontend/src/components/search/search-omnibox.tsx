"use client";

import { useState, useEffect, useRef } from "react";
import { Search, Loader2 } from "lucide-react";
import { Input } from "@/components/ui/input";
import { useQuery } from "@tanstack/react-query";

import axios from "axios";

interface Suggestion {
  text: string;
  type: "PRODUCT" | "CATEGORY";
}

const fetchSuggestions = async (q: string): Promise<Suggestion[]> => {
  if (!q || q.length < 2) return [];
  try {
    const response = await axios.get(`/api/v1/search/suggest?q=${encodeURIComponent(q)}&limit=8`);
    return response.data.suggestions || [];
  } catch (error) {
    console.error("Failed to fetch suggestions:", error);
    return [];
  }
};

export function SearchOmnibox() {
  const [query, setQuery] = useState("");
  const [isOpen, setIsOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);

  const { data: suggestions, isFetching } = useQuery({
    queryKey: ["searchSuggest", query],
    queryFn: () => fetchSuggestions(query),
    enabled: query.length >= 2,
  });

  // Handle clicking outside to close the dropdown
  useEffect(() => {
    const handleClickOutside = (event: MouseEvent) => {
      if (
        containerRef.current &&
        !containerRef.current.contains(event.target as Node)
      ) {
        setIsOpen(false);
      }
    };
    document.addEventListener("mousedown", handleClickOutside);
    return () => document.removeEventListener("mousedown", handleClickOutside);
  }, []);

  return (
    <div
      ref={containerRef}
      className="relative w-full max-w-2xl mx-auto z-50 transition-all duration-300"
    >
      <div className={`relative flex items-center w-full ${isOpen ? "scale-105" : ""} transition-transform duration-300`}>
        <Search className="absolute left-4 h-5 w-5 text-muted-foreground" />
        <Input
          type="text"
          placeholder="Bạn đang tìm kiếm gì hôm nay?"
          className="w-full pl-12 pr-4 py-6 text-lg rounded-full bg-white/5 border border-white/10 text-white placeholder:text-white/30 focus:bg-[#0c0c0f] focus:border-forest-bright focus:ring-2 focus:ring-forest-bright/20 transition-all shadow-sm font-mono tracking-wide"
          value={query}
          onChange={(e) => {
            setQuery(e.target.value);
            setIsOpen(true);
          }}
          onFocus={() => setIsOpen(true)}
        />
        {isFetching && (
          <Loader2 className="absolute right-4 h-5 w-5 animate-spin text-forest-bright" />
        )}
      </div>

      {isOpen && query.length >= 2 && (
        <div className="absolute top-full mt-4 w-full bg-[#0c0c0f] border border-white/10 rounded-2xl shadow-[0_0_50px_rgba(40,200,64,0.1)] overflow-hidden animate-in fade-in slide-in-from-top-4 duration-200">
          <div className="grid grid-cols-12 gap-4 p-4 relative z-10">
            {/* Left Column: Keyword Suggestions */}
            <div className="col-span-5 border-r border-white/10 pr-4">
              <h3 className="text-xs font-bold text-forest-bright uppercase tracking-widest font-mono mb-3">
                Từ khóa gợi ý
              </h3>
              <ul className="space-y-1">
                {suggestions?.length ? (
                  suggestions.map((item, idx) => (
                    <li
                      key={idx}
                      className="flex items-center gap-3 px-3 py-2 rounded-lg hover:bg-white/5 cursor-pointer transition-colors group"
                    >
                      <Search className="h-4 w-4 text-white/30 group-hover:text-forest-bright transition-colors" />
                      <div>
                        <p className="text-sm font-bold text-white/90 group-hover:text-white font-mono">{item.text}</p>
                        <p className="text-xs text-white/40 font-mono">
                          {item.type === "CATEGORY" ? "Danh mục" : "Sản phẩm"}
                        </p>
                      </div>
                    </li>
                  ))
                ) : (
                  !isFetching && (
                    <p className="text-sm text-white/30 italic px-3 font-mono">
                      Không tìm thấy từ khóa phù hợp.
                    </p>
                  )
                )}
              </ul>
            </div>

            {/* Right Column: Product Previews */}
            <div className="col-span-7 pl-2">
              <h3 className="text-xs font-bold text-forest-bright uppercase tracking-widest font-mono mb-3">
                Sản phẩm nổi bật
              </h3>
              <div className="grid grid-cols-2 gap-3">
                {/* Mocked Products for Preview */}
                {[1, 2].map((i) => (
                  <div
                    key={i}
                    className="flex flex-col gap-2 p-3 rounded-xl border border-white/10 hover:border-forest-bright/50 hover:bg-white/5 hover:shadow-[0_0_20px_rgba(40,200,64,0.1)] transition-all cursor-pointer group"
                  >
                    <div className="aspect-video bg-[#1a2e22] rounded-lg overflow-hidden relative border border-forest/20">
                       {/* Placeholder for real image */}
                       <div className="absolute inset-0 bg-[radial-gradient(circle_at_center,_var(--tw-gradient-stops))] from-forest/20 to-transparent group-hover:scale-110 transition-transform duration-500" />
                    </div>
                    <div>
                      <p className="text-sm font-bold text-white/90 line-clamp-1 group-hover:text-white transition-colors">
                        Apple iPhone 15 Pro Max
                      </p>
                      <p className="text-sm text-forest-bright font-black mt-1 tabular-nums font-mono">
                        29.500.000₫
                      </p>
                    </div>
                  </div>
                ))}
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
