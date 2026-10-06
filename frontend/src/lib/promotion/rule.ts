import type { RuleNode, RuleValue } from "@/lib/api/promotion";
import { formatVND } from "@/lib/format";

/**
 * Rule AST của voucher (`rule_schema_version = 1`, Promotion ADR-009).
 *
 * Backend là nơi quyết định cuối cùng; các giới hạn dưới đây phản chiếu
 * `RuleValidator`/`RuleFields` để báo lỗi sớm bằng tiếng Việt, không phải để thay thế.
 * Contract chỉ nói `field` "phải thuộc allowlist" mà không liệt kê, nên danh sách field
 * nằm ở đây.
 */

export const RULE_SCHEMA_VERSION = 1;
export const MAX_RULE_DEPTH = 8;
export const MAX_RULE_NODES = 200;
export const MAX_RULE_CHILDREN = 50;
export const MAX_RULE_COLLECTION = 100;
export const MAX_RULE_STRING = 255;

export type ConditionOperator =
  | "EQUAL"
  | "NOT_EQUAL"
  | "IN"
  | "NOT_IN"
  | "GREATER_THAN"
  | "GREATER_THAN_OR_EQUAL"
  | "LESS_THAN"
  | "LESS_THAN_OR_EQUAL";

export type LogicalOperator = "AND" | "OR";

export const OPERATOR_LABELS: Record<ConditionOperator, string> = {
  EQUAL: "bằng",
  NOT_EQUAL: "khác",
  IN: "thuộc một trong",
  NOT_IN: "không thuộc",
  GREATER_THAN: "lớn hơn",
  GREATER_THAN_OR_EQUAL: "từ (≥)",
  LESS_THAN: "nhỏ hơn",
  LESS_THAN_OR_EQUAL: "tối đa (≤)",
};

export const LOGICAL_LABELS: Record<LogicalOperator, { name: string; hint: string }> = {
  AND: { name: "Tất cả điều kiện", hint: "Đơn hàng phải đạt mọi điều kiện bên dưới." },
  OR: { name: "Một trong các điều kiện", hint: "Chỉ cần đạt một điều kiện bên dưới." },
};

export type RuleFieldKind = "money" | "categories" | "payment-method" | "text" | "uuid";

export interface RuleFieldSpec {
  id: string;
  label: string;
  hint: string;
  kind: RuleFieldKind;
  operators: readonly ConditionOperator[];
  /** `false`: không cho thêm điều kiện mới, nhưng vẫn hiển thị nếu rule cũ đang có. */
  selectable: boolean;
}

const NUMERIC_OPERATORS: readonly ConditionOperator[] = [
  "GREATER_THAN_OR_EQUAL",
  "GREATER_THAN",
  "LESS_THAN_OR_EQUAL",
  "LESS_THAN",
  "EQUAL",
  "NOT_EQUAL",
];

export const RULE_FIELDS: readonly RuleFieldSpec[] = [
  {
    id: "cart.total_amount",
    label: "Tổng tiền hàng của đơn",
    hint: "Tạm tính trước giảm giá, tính theo VND.",
    kind: "money",
    operators: NUMERIC_OPERATORS,
    selectable: true,
  },
  {
    id: "cart.items.category_id",
    label: "Danh mục của sản phẩm trong đơn",
    hint: "“Thuộc” đạt khi có ít nhất một sản phẩm trong các danh mục đã chọn (kể cả qua danh mục cha); “không thuộc” đạt khi không sản phẩm nào nằm trong đó.",
    kind: "categories",
    operators: ["IN", "NOT_IN"],
    selectable: true,
  },
  {
    id: "cart.payment_method",
    label: "Phương thức thanh toán",
    hint: "Hiện chỉ hỗ trợ Stripe.",
    kind: "payment-method",
    operators: ["EQUAL", "NOT_EQUAL"],
    selectable: true,
  },
  {
    id: "cart.checkout_channel",
    label: "Kênh đặt hàng",
    hint: "Checkout hiện luôn gửi kênh CHECKOUT.",
    kind: "text",
    operators: ["EQUAL", "NOT_EQUAL"],
    selectable: true,
  },
  {
    id: "user.id",
    label: "Mã người dùng",
    hint: "UUID của tài khoản. Dùng để giới hạn voucher cho một số người cụ thể.",
    kind: "uuid",
    operators: ["IN", "NOT_IN", "EQUAL", "NOT_EQUAL"],
    selectable: true,
  },
  {
    id: "user.tier",
    label: "Hạng thành viên",
    hint: "Chưa có nguồn dữ liệu hạng nên điều kiện này luôn không đạt.",
    kind: "text",
    operators: ["EQUAL", "NOT_EQUAL", "IN", "NOT_IN"],
    selectable: false,
  },
];

export const PAYMENT_METHOD_OPTIONS = [{ value: "STRIPE", label: "Stripe" }] as const;

export function fieldSpec(fieldId: string): RuleFieldSpec | undefined {
  return RULE_FIELDS.find((field) => field.id === fieldId);
}

export function isListOperator(operator: ConditionOperator): boolean {
  return operator === "IN" || operator === "NOT_IN";
}

export type DraftNode = DraftLogical | DraftCondition;

export interface DraftLogical {
  node_type: "LOGICAL";
  operator: LogicalOperator;
  children: DraftNode[];
}

export interface DraftCondition {
  node_type: "CONDITION";
  operator: ConditionOperator;
  field: string;
  /** `null` = chưa nhập. Chỉ hợp lệ để lưu trong lúc soạn, không gửi được. */
  value: RuleValue | null;
}

/**
 * Contract không có cách nói “không thêm điều kiện nào”: gốc phải là một node và
 * logical rỗng luôn đánh giá `false`. Tổng tiền hàng không bao giờ âm nên điều kiện
 * dưới đây luôn đúng; UI dùng nó cho lựa chọn “Áp dụng cho mọi đơn hàng”.
 */
export const ALWAYS_TRUE_RULE: RuleNode = {
  node_type: "CONDITION",
  operator: "GREATER_THAN_OR_EQUAL",
  field: "cart.total_amount",
  value: 0,
};

export function isAlwaysTrue(node: DraftNode | RuleNode): boolean {
  return (
    node.node_type === "CONDITION" &&
    node.field === "cart.total_amount" &&
    node.operator === "GREATER_THAN_OR_EQUAL" &&
    node.value === 0
  );
}

export function emptyGroup(operator: LogicalOperator = "AND"): DraftLogical {
  return { node_type: "LOGICAL", operator, children: [] };
}

export function newCondition(fieldId: string = "cart.total_amount"): DraftCondition {
  const spec = fieldSpec(fieldId) ?? RULE_FIELDS[0];
  return {
    node_type: "CONDITION",
    field: spec.id,
    operator: spec.operators[0],
    value: defaultValue(spec, spec.operators[0]),
  };
}

export function defaultValue(spec: RuleFieldSpec, operator: ConditionOperator): RuleValue | null {
  if (isListOperator(operator)) return [];
  if (spec.kind === "payment-method") return PAYMENT_METHOD_OPTIONS[0].value;
  if (spec.kind === "text" && spec.id === "cart.checkout_channel") return "CHECKOUT";
  return null;
}

/** Đổi operator giữ được giá trị khi dạng (đơn/danh sách) không đổi, ngược lại làm lại. */
export function changeOperator(
  condition: DraftCondition,
  operator: ConditionOperator
): DraftCondition {
  const spec = fieldSpec(condition.field);
  const wasList = isListOperator(condition.operator);
  const isList = isListOperator(operator);
  if (wasList === isList || !spec) return { ...condition, operator };
  const value = condition.value;
  if (isList) {
    const carried = typeof value === "string" || typeof value === "number" ? [value] : [];
    return { ...condition, operator, value: carried };
  }
  const first = Array.isArray(value) ? (value[0] ?? null) : value;
  return { ...condition, operator, value: first };
}

export function fromWire(node: RuleNode): DraftNode {
  if (node.node_type === "LOGICAL") {
    return { ...node, children: node.children.map(fromWire) };
  }
  return { ...node };
}

export function toWire(node: DraftNode): RuleNode {
  if (node.node_type === "LOGICAL") {
    return { ...node, children: node.children.map(toWire) };
  }
  return { ...node, value: node.value as RuleValue };
}

export function countNodes(node: DraftNode): number {
  return node.node_type === "LOGICAL"
    ? 1 + node.children.reduce((total, child) => total + countNodes(child), 0)
    : 1;
}

export function depthOf(node: DraftNode): number {
  return node.node_type === "LOGICAL"
    ? 1 + node.children.reduce((deepest, child) => Math.max(deepest, depthOf(child)), 0)
    : 1;
}

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export function isUuid(value: string): boolean {
  return UUID_PATTERN.test(value);
}

/** Danh sách lỗi tiếng Việt, rỗng khi rule hợp lệ. Tối đa một lỗi cho mỗi điều kiện. */
export function validateRule(root: DraftNode): string[] {
  const errors: string[] = [];
  if (countNodes(root) > MAX_RULE_NODES) {
    errors.push(`Điều kiện có quá ${MAX_RULE_NODES} nút. Hãy rút gọn.`);
  }
  if (depthOf(root) > MAX_RULE_DEPTH) {
    errors.push(`Các nhóm điều kiện lồng nhau quá ${MAX_RULE_DEPTH} cấp.`);
  }
  walk(root, "Điều kiện", errors);
  return errors;
}

function walk(node: DraftNode, path: string, errors: string[]) {
  if (node.node_type === "LOGICAL") {
    if (node.children.length === 0) {
      errors.push(`${path}: nhóm chưa có điều kiện nào.`);
      return;
    }
    if (node.children.length > MAX_RULE_CHILDREN) {
      errors.push(`${path}: một nhóm chỉ có tối đa ${MAX_RULE_CHILDREN} điều kiện.`);
    }
    node.children.forEach((child, index) => walk(child, `${path} › ${index + 1}`, errors));
    return;
  }

  const spec = fieldSpec(node.field);
  if (!spec) {
    errors.push(`${path}: trường “${node.field}” không được hỗ trợ.`);
    return;
  }
  if (!spec.operators.includes(node.operator)) {
    errors.push(`${path}: “${spec.label}” không dùng được phép so sánh này.`);
    return;
  }
  const problem = validateValue(spec, node.operator, node.value);
  if (problem) errors.push(`${path}: ${problem}`);
}

function validateScalar(spec: RuleFieldSpec, value: unknown): string | null {
  if (spec.kind === "money") {
    return typeof value === "number" && Number.isSafeInteger(value) && value >= 0
      ? null
      : "hãy nhập số tiền nguyên từ 0 trở lên.";
  }
  if (typeof value !== "string" || value.trim() === "") return "hãy nhập giá trị.";
  if (value.length > MAX_RULE_STRING) return `giá trị dài quá ${MAX_RULE_STRING} ký tự.`;
  if (spec.kind === "uuid" && !isUuid(value)) return "mã người dùng phải là UUID hợp lệ.";
  return null;
}

function validateValue(
  spec: RuleFieldSpec,
  operator: ConditionOperator,
  value: RuleValue | null
): string | null {
  if (isListOperator(operator)) {
    if (!Array.isArray(value) || value.length === 0) {
      return spec.kind === "categories" ? "hãy chọn ít nhất một danh mục." : "hãy nhập ít nhất một giá trị.";
    }
    if (value.length > MAX_RULE_COLLECTION) return `tối đa ${MAX_RULE_COLLECTION} giá trị.`;
    if (new Set(value).size !== value.length) return "các giá trị không được trùng nhau.";
    for (const item of value) {
      const problem = validateScalar(spec, item);
      if (problem) return problem;
    }
    return null;
  }
  if (Array.isArray(value) || typeof value === "boolean") {
    return "hãy nhập giá trị.";
  }
  return validateScalar(spec, value);
}

/** Câu mô tả rule cho người đọc; `categoryName` tra tên danh mục từ ID. */
export function describeRule(
  node: DraftNode | RuleNode,
  categoryName: (categoryId: string) => string = (id) => id
): string {
  return describe(node as DraftNode, categoryName, true);
}

function describe(
  node: DraftNode,
  categoryName: (categoryId: string) => string,
  isRoot: boolean
): string {
  if (isAlwaysTrue(node)) return "Mọi đơn hàng";
  if (node.node_type === "LOGICAL") {
    const parts = node.children.map((child) => describe(child, categoryName, false));
    if (parts.length === 0) return "(chưa có điều kiện)";
    const joiner = node.operator === "AND" ? " và " : " hoặc ";
    const text = parts.join(joiner);
    return isRoot || parts.length === 1 ? text : `(${text})`;
  }

  const spec = fieldSpec(node.field);
  const subject = spec?.label ?? node.field;
  const operator = OPERATOR_LABELS[node.operator] ?? node.operator;
  return `${subject} ${operator} ${describeValue(node, categoryName)}`;
}

function describeValue(
  node: DraftCondition,
  categoryName: (categoryId: string) => string
): string {
  const spec = fieldSpec(node.field);
  const render = (item: unknown): string => {
    if (spec?.kind === "money" && typeof item === "number") return formatVND(item);
    if (spec?.kind === "categories") return categoryName(String(item));
    if (spec?.kind === "payment-method") {
      return PAYMENT_METHOD_OPTIONS.find((option) => option.value === item)?.label ?? String(item);
    }
    return String(item);
  };
  if (Array.isArray(node.value)) return `[${node.value.map(render).join(", ")}]`;
  return node.value === null ? "…" : render(node.value);
}
