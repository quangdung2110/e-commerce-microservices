import type {
  Attribute,
  AttributeValue,
  AttributeValues,
  Category,
  CategoryAttribute,
} from "@/lib/api/product";

export interface CategoryTreeItem {
  category: Category;
  depth: number;
  isLeaf: boolean;
}

/** Danh sách phẳng theo thứ tự cây, dùng chung cho tree và các ô chọn category. */
export function flattenCategoryTree(categories: Category[]): CategoryTreeItem[] {
  const byParent = new Map<string | null, Category[]>();
  const ids = new Set(categories.map((category) => category.category_id));

  for (const category of categories) {
    const parent = category.parent_id && ids.has(category.parent_id) ? category.parent_id : null;
    const siblings = byParent.get(parent) ?? [];
    siblings.push(category);
    byParent.set(parent, siblings);
  }

  for (const siblings of byParent.values()) {
    siblings.sort((left, right) => left.name.localeCompare(right.name, "vi"));
  }

  const result: CategoryTreeItem[] = [];
  const visited = new Set<string>();

  function visit(category: Category, depth: number) {
    if (visited.has(category.category_id)) return;
    visited.add(category.category_id);
    const children = byParent.get(category.category_id) ?? [];
    result.push({ category, depth, isLeaf: children.length === 0 });
    for (const child of children) visit(child, depth + 1);
  }

  for (const root of byParent.get(null) ?? []) visit(root, 0);
  // Dữ liệu lỗi (cycle/orphan) vẫn hiện để admin có thể nhận biết và xử lý.
  for (const category of categories) visit(category, 0);
  return result;
}

export function slugifyCatalogName(value: string): string {
  return value
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .replace(/đ/g, "d")
    .replace(/Đ/g, "D")
    .toLowerCase()
    .trim()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 160);
}

export function attributeByCode(attributes: Attribute[]): Map<string, Attribute> {
  return new Map(attributes.map((attribute) => [attribute.code, attribute]));
}

/** Chuẩn hoá dữ liệu form theo type của mapping trước khi gửi Product Service. */
export function normalizeAttributeValues(
  mappings: CategoryAttribute[],
  values: Record<string, AttributeValue | "">
): AttributeValues {
  const result: AttributeValues = {};
  for (const mapping of mappings) {
    const value = values[mapping.attribute_code];
    if (value === "" || value === undefined) continue;

    if (mapping.value_type === "NUMBER") {
      const number = typeof value === "number" ? value : Number(value);
      if (Number.isFinite(number)) result[mapping.attribute_code] = number;
    } else if (mapping.value_type === "BOOLEAN") {
      result[mapping.attribute_code] = value === true || value === "true";
    } else {
      result[mapping.attribute_code] = value;
    }
  }
  return result;
}

export function missingRequiredAttributes(
  mappings: CategoryAttribute[],
  values: Record<string, AttributeValue | "">
): string[] {
  return mappings
    .filter((mapping) => mapping.is_required)
    .filter((mapping) => values[mapping.attribute_code] === "" || values[mapping.attribute_code] === undefined)
    .map((mapping) => mapping.attribute_name);
}
