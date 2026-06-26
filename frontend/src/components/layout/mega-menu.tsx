"use client";

import { ChevronDown, Smartphone, Laptop, Headphones, Watch, Camera, Layers } from "lucide-react";
import { useState, useEffect } from "react";
import axios from "axios";

// Map standard category IDs to icons
const getCategoryIcon = (categoryId: string) => {
  if (categoryId.includes("smartphone")) return Smartphone;
  if (categoryId.includes("laptop")) return Laptop;
  if (categoryId.includes("audio") || categoryId.includes("headphone")) return Headphones;
  if (categoryId.includes("watch")) return Watch;
  if (categoryId.includes("camera")) return Camera;
  return Layers;
};

export function MegaMenu() {
  const [activeCategory, setActiveCategory] = useState<string | null>(null);
  const [categories, setCategories] = useState<any[]>([]);

  useEffect(() => {
    const fetchCategories = async () => {
      try {
        // Next.js rewrites rules will proxy this to search-service
        const res = await axios.get("/api/v1/search/categories");
        if (res.data && res.data.categories) {
          setCategories(res.data.categories);
        }
      } catch (err) {
        console.error("Failed to load categories", err);
      }
    };
    fetchCategories();
  }, []);

  return (
    <nav className="relative flex items-center gap-1 py-3" onMouseLeave={() => setActiveCategory(null)}>
      {categories.map((cat) => {
        const Icon = getCategoryIcon(cat.category_id);
        return (
          <div
            key={cat.category_id}
            className="group"
            onMouseEnter={() => setActiveCategory(cat.category_id)}
          >
            <button className="flex items-center gap-2 px-4 py-2 rounded-full text-sm font-medium hover:bg-white/[0.06] transition-all duration-300 text-white/55 hover:text-white">
              <Icon className="h-4 w-4" />
              {cat.name}
              <ChevronDown className="h-3 w-3 opacity-50 group-hover:rotate-180 transition-transform duration-300" />
            </button>
            
            {/* Dropdown Panel */}
            {activeCategory === cat.category_id && (
              <div className="absolute top-full left-0 w-full pt-4 animate-in fade-in slide-in-from-top-2 duration-300">
                <div className="bg-[#0c0c0f] border border-white/10 shadow-[0_30px_80px_-20px_rgba(0,0,0,0.8)] rounded-2xl p-6 grid grid-cols-4 gap-8">
                  {/* Subcategories */}
                  <div className="col-span-1 space-y-4">
                    <h4 className="font-bold text-white/90 font-mono text-sm tracking-wide uppercase">Danh mục con</h4>
                    <ul className="space-y-2">
                      {cat.sub_categories?.map((sub: any) => (
                        <li key={sub.category_id}>
                          <a href={`/?category=${sub.category_id}`} className="text-sm text-white/55 hover:text-forest-bright transition-colors duration-300">
                            {sub.name} <span className="text-xs opacity-40 font-mono ml-1">({sub.product_count})</span>
                          </a>
                        </li>
                      ))}
                      {(!cat.sub_categories || cat.sub_categories.length === 0) && (
                        <li className="text-sm text-white/30 font-mono italic">Tất cả sản phẩm {cat.name}</li>
                      )}
                    </ul>
                  </div>
                  <div className="col-span-1 space-y-4">
                    <h4 className="font-bold text-white/90 font-mono text-sm tracking-wide uppercase">Mức giá</h4>
                    <ul className="space-y-2">
                      {["Dưới 5 triệu", "5 - 15 triệu", "15 - 30 triệu", "Trên 30 triệu"].map((price) => (
                        <li key={price}>
                          <a href="#" className="text-sm text-white/55 hover:text-forest-bright transition-colors duration-300">
                            {price}
                          </a>
                        </li>
                      ))}
                    </ul>
                  </div>
                  {/* Promo Banner */}
                  <div className="col-span-2 rounded-xl bg-[#0a140f] border border-forest/20 p-6 flex flex-col justify-center items-start relative overflow-hidden group/banner">
                    <div className="absolute inset-0 bg-[radial-gradient(ellipse_at_center,_var(--tw-gradient-stops))] from-forest/10 via-transparent to-transparent group-hover/banner:scale-110 transition-transform duration-700" />
                    {/* Stars / Particles */}
                    <span className="absolute top-4 right-8 h-1 w-1 rounded-full bg-forest-bright shadow-[0_0_5px_rgba(40,200,64,0.8)] animate-pulse"></span>
                    <span className="absolute bottom-6 right-20 h-0.5 w-0.5 rounded-full bg-white shadow-[0_0_2px_white] animate-ping"></span>
                    
                    <h3 className="text-2xl font-black text-white relative z-10 mb-2">
                      Siêu hội công nghệ
                    </h3>
                    <p className="text-sm text-white/55 relative z-10 mb-6 max-w-[250px]">
                      Khám phá ngay hàng ngàn sản phẩm giảm giá cực sốc.
                    </p>
                    <button className="relative z-10 px-6 py-2.5 bg-forest-bright text-black rounded-full text-sm font-bold shadow-[0_0_15px_rgba(40,200,64,0.3)] hover:shadow-[0_0_25px_rgba(40,200,64,0.5)] hover:scale-105 transition-all duration-300">
                      Mua ngay
                    </button>
                  </div>
                </div>
              </div>
            )}
          </div>
        );
      })}
    </nav>
  );
}
