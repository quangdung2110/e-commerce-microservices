import { create } from "zustand";

interface SearchState {
  category: string | null;
  sort: string;
  priceMin: number | null;
  priceMax: number | null;
  inStock: boolean;
  hasPromotion: boolean;
  filters: Record<string, string[]>;
  total: number;
  facets: any | null;
  setFacets: (facets: any) => void;
  setTotal: (total: number) => void;
  setCategory: (category: string | null) => void;
  setSort: (sort: string) => void;
  setPriceRange: (min: number | null, max: number | null) => void;
  toggleFilter: (key: string, value: string) => void;
  setStockPromotion: (inStock: boolean, hasPromotion: boolean) => void;
  clearFilters: () => void;
}

export const useSearchStore = create<SearchState>((set) => ({
  category: null,
  sort: "relevance",
  priceMin: null,
  priceMax: null,
  inStock: true,
  hasPromotion: false,
  filters: {},
  total: 0,
  facets: null,
  setFacets: (facets) => set({ facets }),
  setTotal: (total) => set({ total }),
  setCategory: (category) => set({ category }),
  setSort: (sort) => set({ sort }),
  setPriceRange: (priceMin, priceMax) => set({ priceMin, priceMax }),
  toggleFilter: (key, value) =>
    set((state) => {
      const current = state.filters[key] || [];
      const updated = current.includes(value)
        ? current.filter((v) => v !== value)
        : [...current, value];
      return {
        filters: {
          ...state.filters,
          [key]: updated,
        },
      };
    }),
  setStockPromotion: (inStock, hasPromotion) => set({ inStock, hasPromotion }),
  clearFilters: () =>
    set({
      category: null,
      priceMin: null,
      priceMax: null,
      inStock: true,
      hasPromotion: false,
      filters: {},
    }),
}));
