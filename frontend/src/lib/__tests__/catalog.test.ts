import { describe, expect, it } from "vitest";

import {
  flattenCategoryTree,
  missingRequiredAttributes,
  normalizeAttributeValues,
  slugifyCatalogName,
} from "@/lib/catalog";
import type { Category, CategoryAttribute } from "@/lib/api/product";

const now = "2026-09-29T00:00:00Z";
const category = (id: string, name: string, parentId: string | null = null): Category => ({
  category_id: id,
  parent_id: parentId,
  name,
  slug: name.toLowerCase(),
  category_revision: 1,
  attributes: [],
  created_at: now,
  updated_at: now,
});

describe("catalog helpers", () => {
  it("sắp category theo cây và nhận biết lá", () => {
    const result = flattenCategoryTree([
      category("child", "Điện thoại", "root"),
      category("root", "Thiết bị"),
      category("other", "Âm thanh"),
    ]);

    expect(result.map(({ category: item, depth, isLeaf }) => [item.category_id, depth, isLeaf])).toEqual([
      ["other", 0, true],
      ["root", 0, false],
      ["child", 1, true],
    ]);
  });

  it("tạo slug ASCII ổn định", () => {
    expect(slugifyCatalogName("  Điện thoại & Phụ kiện  ")).toBe("dien-thoai-phu-kien");
  });

  it("chuẩn hoá number và boolean, đồng thời bỏ field trống", () => {
    const mappings: CategoryAttribute[] = [
      {
        category_id: "c",
        attribute_id: "a1",
        attribute_code: "weight",
        attribute_name: "Khối lượng",
        value_type: "NUMBER",
        is_required: true,
        is_variant: false,
      },
      {
        category_id: "c",
        attribute_id: "a2",
        attribute_code: "wireless",
        attribute_name: "Không dây",
        value_type: "BOOLEAN",
        is_required: false,
        is_variant: false,
      },
    ];

    expect(normalizeAttributeValues(mappings, { weight: "120", wireless: "false" })).toEqual({
      weight: 120,
      wireless: false,
    });
    expect(missingRequiredAttributes(mappings, { weight: "", wireless: true })).toEqual([
      "Khối lượng",
    ]);
  });
});
