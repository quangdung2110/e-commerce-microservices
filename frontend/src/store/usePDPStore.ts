import { create } from "zustand";

// ─── Types ──────────────────────────────────────────────────────

export interface VariantOption {
  /** The attribute name, e.g. "color", "storage" */
  name: string;
  /** Display label for the attribute, e.g. "Màu sắc", "Dung lượng" */
  label: string;
  /** All possible values for this attribute */
  values: string[];
}

export interface SkuVariant {
  sku_id: string;
  sku_code?: string;
  attributes: Record<string, string>;
  original_price: number;
  promotional_price: number | null;
  best_price: number;
  has_promotion: boolean;
  image_url?: string;
  display_quantity: number;
  is_available: boolean;
  sku_status: string;
}

// ─── Store ──────────────────────────────────────────────────────

interface PDPStore {
  /** Product ID */
  productId: string | null;
  /** All available variants for this product */
  variants: SkuVariant[];
  /** Available variant options (for building the picker UI) */
  variantOptions: VariantOption[];
  /** Currently selected variant attributes, e.g. { color: "Đen", storage: "256GB" } */
  selectedAttributes: Record<string, string>;
  /** Selected quantity */
  quantity: number;
  /** Is adding to cart */
  isAddingToCart: boolean;

  // Actions
  setProduct: (productId: string, variants: SkuVariant[]) => void;
  setSelectedAttribute: (name: string, value: string) => void;
  setQuantity: (qty: number) => void;
  incrementQuantity: () => void;
  decrementQuantity: () => void;
  setIsAddingToCart: (adding: boolean) => void;
  reset: () => void;

  // Computed
  /** Get the currently selected SKU based on selected attributes */
  getSelectedSku: () => SkuVariant | null;
  /** Get max quantity available (min of stock, 100) */
  getMaxQuantity: () => number;
  /** Get discount percentage of selected SKU */
  getDiscountPercentage: () => number;
}

export const usePDPStore = create<PDPStore>((set, get) => ({
  productId: null,
  variants: [],
  variantOptions: [],
  selectedAttributes: {},
  quantity: 1,
  isAddingToCart: false,

  setProduct: (productId, variants) => {
    // Build variant options from all variants' attributes
    const optionMap = new Map<string, Set<string>>();
    const labelMap: Record<string, string> = {
      color: "Màu sắc",
      colour: "Màu sắc",
      size: "Kích thước",
      storage: "Dung lượng",
      ram: "RAM",
      material: "Chất liệu",
    };

    variants.forEach((v) => {
      Object.entries(v.attributes).forEach(([key, value]) => {
        if (!optionMap.has(key)) optionMap.set(key, new Set());
        optionMap.get(key)!.add(value);
      });
    });

    const variantOptions: VariantOption[] = Array.from(optionMap.entries()).map(
      ([name, values]) => ({
        name,
        label: labelMap[name] || name,
        values: Array.from(values),
      })
    );

    // Auto-select first variant's attributes
    const firstVariant = variants[0];
    const selectedAttributes: Record<string, string> = {};
    if (firstVariant) {
      // For each option, pick the first variant's value if it has it
      variantOptions.forEach((opt) => {
        const matchingValue = firstVariant.attributes[opt.name];
        if (matchingValue) {
          selectedAttributes[opt.name] = matchingValue;
        } else {
          // Fallback to first value
          selectedAttributes[opt.name] = opt.values[0];
        }
      });
    }

    set({
      productId,
      variants,
      variantOptions,
      selectedAttributes,
      quantity: 1,
    });
  },

  setSelectedAttribute: (name, value) =>
    set((state) => ({
      selectedAttributes: { ...state.selectedAttributes, [name]: value },
      quantity: 1, // Reset quantity on variant change
    })),

  setQuantity: (qty) => {
    const max = get().getMaxQuantity();
    set({ quantity: Math.max(1, Math.min(qty, max)) });
  },

  incrementQuantity: () => {
    const max = get().getMaxQuantity();
    set((state) => ({ quantity: Math.min(state.quantity + 1, max) }));
  },

  decrementQuantity: () =>
    set((state) => ({ quantity: Math.max(1, state.quantity - 1) })),

  setIsAddingToCart: (isAddingToCart) => set({ isAddingToCart }),

  reset: () =>
    set({
      productId: null,
      variants: [],
      variantOptions: [],
      selectedAttributes: {},
      quantity: 1,
      isAddingToCart: false,
    }),

  getSelectedSku: () => {
    const { variants, selectedAttributes } = get();
    return (
      variants.find((v) =>
        Object.entries(selectedAttributes).every(
          ([key, value]) => v.attributes[key] === value
        )
      ) || null
    );
  },

  getMaxQuantity: () => {
    const sku = get().getSelectedSku();
    if (!sku) return 1;
    return Math.min(sku.display_quantity > 0 ? sku.display_quantity : 0, 100);
  },

  getDiscountPercentage: () => {
    const sku = get().getSelectedSku();
    if (!sku || sku.original_price <= 0) return 0;
    return Math.round(
      ((sku.original_price - sku.best_price) / sku.original_price) * 100
    );
  },
}));
