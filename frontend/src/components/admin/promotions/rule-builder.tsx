"use client";

import { useId, useMemo, useState } from "react";
import { Plus, Trash2, X } from "lucide-react";

import { MoneyInput } from "@/components/admin/money-input";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { NativeSelect } from "@/components/ui/native-select";
import { Switch } from "@/components/ui/switch";
import type { Category } from "@/lib/api/product";
import { flattenCategoryTree } from "@/lib/catalog";
import {
  ALWAYS_TRUE_RULE,
  LOGICAL_LABELS,
  MAX_RULE_DEPTH,
  OPERATOR_LABELS,
  PAYMENT_METHOD_OPTIONS,
  RULE_FIELDS,
  changeOperator,
  describeRule,
  emptyGroup,
  fieldSpec,
  fromWire,
  isAlwaysTrue,
  isListOperator,
  newCondition,
  type ConditionOperator,
  type DraftCondition,
  type DraftLogical,
  type DraftNode,
  type LogicalOperator,
  type RuleFieldSpec,
} from "@/lib/promotion/rule";

interface RuleBuilderProps {
  value: DraftNode;
  onChange: (next: DraftNode) => void;
  categories: Category[];
  categoriesError?: boolean;
  disabled?: boolean;
  errors: string[];
}

export function RuleBuilder({
  value,
  onChange,
  categories,
  categoriesError = false,
  disabled = false,
  errors,
}: RuleBuilderProps) {
  const matchAll = isAlwaysTrue(value);
  const categoryNames = useMemo(
    () => new Map(categories.map((category) => [category.category_id, category.name])),
    [categories]
  );
  // Rule cũ có thể có gốc là một điều kiện đơn; hiển thị như nhóm “tất cả” chỉ có một điều kiện.
  const group: DraftLogical =
    value.node_type === "LOGICAL" ? value : { ...emptyGroup("AND"), children: [value] };

  return (
    <div className="space-y-5">
      <div className="flex items-start justify-between gap-6 rounded-lg border border-border bg-surface-2 p-4">
        <div className="space-y-1">
          <Label htmlFor="rule-match-all">Áp dụng cho mọi đơn hàng</Label>
          <p className="text-caption text-muted-foreground">
            Bật khi voucher chỉ bị giới hạn bởi giá trị đơn tối thiểu. Tắt để đặt thêm điều kiện về danh mục, người dùng…
          </p>
        </div>
        <Switch
          id="rule-match-all"
          checked={matchAll}
          disabled={disabled}
          onCheckedChange={(checked) =>
            onChange(
              checked
                ? fromWire(ALWAYS_TRUE_RULE)
                : { ...emptyGroup("AND"), children: [newCondition("cart.items.category_id")] }
            )
          }
        />
      </div>

      {matchAll ? null : (
        <GroupEditor
          node={group}
          depth={1}
          disabled={disabled}
          categories={categories}
          categoriesError={categoriesError}
          onChange={onChange}
        />
      )}

      {errors.length > 0 ? (
        <ul role="alert" className="space-y-1 text-caption text-danger">
          {errors.map((message) => (
            <li key={message}>{message}</li>
          ))}
        </ul>
      ) : (
        <p className="text-caption text-muted-foreground" aria-live="polite">
          Tóm tắt: {describeRule(value, (id) => categoryNames.get(id) ?? "danh mục không còn tồn tại")}
        </p>
      )}
    </div>
  );
}

interface GroupEditorProps {
  node: DraftLogical;
  depth: number;
  disabled: boolean;
  categories: Category[];
  categoriesError: boolean;
  onChange: (next: DraftLogical) => void;
  onRemove?: () => void;
}

function GroupEditor({ node, depth, disabled, categories, categoriesError, onChange, onRemove }: GroupEditorProps) {
  const operatorId = useId();
  const canNestGroup = depth + 2 <= MAX_RULE_DEPTH;

  function replaceChild(index: number, next: DraftNode) {
    onChange({ ...node, children: node.children.map((child, at) => (at === index ? next : child)) });
  }
  function removeChild(index: number) {
    onChange({ ...node, children: node.children.filter((_, at) => at !== index) });
  }

  return (
    <div className={depth > 1 ? "space-y-4 rounded-lg border border-border bg-surface-2 p-4" : "space-y-4"}>
      <div className="flex flex-wrap items-end gap-3">
        <div className="min-w-56 flex-1 space-y-2">
          <Label htmlFor={operatorId}>Đơn hàng cần đạt</Label>
          <NativeSelect
            id={operatorId}
            value={node.operator}
            disabled={disabled}
            onChange={(event) => onChange({ ...node, operator: event.target.value as LogicalOperator })}
          >
            {(Object.keys(LOGICAL_LABELS) as LogicalOperator[]).map((operator) => (
              <option key={operator} value={operator}>{LOGICAL_LABELS[operator].name}</option>
            ))}
          </NativeSelect>
        </div>
        {onRemove ? (
          <Button type="button" variant="ghost" size="sm" disabled={disabled} onClick={onRemove}>
            <Trash2 aria-hidden />
            Xoá nhóm
          </Button>
        ) : null}
      </div>
      <p className="text-caption text-muted-foreground">{LOGICAL_LABELS[node.operator].hint}</p>

      <ul className="space-y-3">
        {node.children.map((child, index) => (
          <li key={index}>
            {child.node_type === "LOGICAL" ? (
              <GroupEditor
                node={child}
                depth={depth + 1}
                disabled={disabled}
                categories={categories}
                categoriesError={categoriesError}
                onChange={(next) => replaceChild(index, next)}
                onRemove={() => removeChild(index)}
              />
            ) : (
              <ConditionRow
                node={child}
                index={index}
                disabled={disabled}
                categories={categories}
                categoriesError={categoriesError}
                onChange={(next) => replaceChild(index, next)}
                onRemove={() => removeChild(index)}
              />
            )}
          </li>
        ))}
      </ul>

      <div className="flex flex-wrap gap-2">
        <Button
          type="button"
          variant="outline"
          size="sm"
          disabled={disabled}
          onClick={() => onChange({ ...node, children: [...node.children, newCondition()] })}
        >
          <Plus aria-hidden />
          Thêm điều kiện
        </Button>
        {canNestGroup ? (
          <Button
            type="button"
            variant="ghost"
            size="sm"
            disabled={disabled}
            onClick={() =>
              onChange({
                ...node,
                children: [
                  ...node.children,
                  { ...emptyGroup(node.operator === "AND" ? "OR" : "AND"), children: [newCondition()] },
                ],
              })
            }
          >
            <Plus aria-hidden />
            Thêm nhóm
          </Button>
        ) : null}
      </div>
    </div>
  );
}

interface ConditionRowProps {
  node: DraftCondition;
  index: number;
  disabled: boolean;
  categories: Category[];
  categoriesError: boolean;
  onChange: (next: DraftCondition) => void;
  onRemove: () => void;
}

function ConditionRow({ node, index, disabled, categories, categoriesError, onChange, onRemove }: ConditionRowProps) {
  const baseId = useId();
  const spec = fieldSpec(node.field);
  const fields = RULE_FIELDS.filter((field) => field.selectable || field.id === node.field);
  const operators = spec?.operators ?? [node.operator];

  return (
    <fieldset className="space-y-3 rounded-lg border border-border bg-surface-overlay p-4" disabled={disabled}>
      <legend className="px-1 text-caption text-muted-foreground">Điều kiện {index + 1}</legend>
      <div className="grid gap-3 md:grid-cols-[minmax(0,1.3fr)_minmax(0,1fr)_auto] md:items-end">
        <div className="space-y-2">
          <Label htmlFor={`${baseId}-field`}>Áp dụng cho</Label>
          <NativeSelect
            id={`${baseId}-field`}
            value={node.field}
            onChange={(event) => onChange(newCondition(event.target.value))}
          >
            {spec ? null : <option value={node.field}>{node.field} (không hỗ trợ)</option>}
            {fields.map((field) => (
              <option key={field.id} value={field.id}>{field.label}</option>
            ))}
          </NativeSelect>
        </div>
        <div className="space-y-2">
          <Label htmlFor={`${baseId}-operator`}>Phép so sánh</Label>
          <NativeSelect
            id={`${baseId}-operator`}
            value={node.operator}
            onChange={(event) => onChange(changeOperator(node, event.target.value as ConditionOperator))}
          >
            {operators.map((operator) => (
              <option key={operator} value={operator}>{OPERATOR_LABELS[operator]}</option>
            ))}
          </NativeSelect>
        </div>
        <Button
          type="button"
          variant="ghost"
          size="icon"
          aria-label={`Xoá điều kiện ${index + 1}`}
          disabled={disabled}
          onClick={onRemove}
        >
          <Trash2 aria-hidden />
        </Button>
      </div>

      {spec ? (
        <ValueEditor
          id={`${baseId}-value`}
          spec={spec}
          node={node}
          categories={categories}
          categoriesError={categoriesError}
          onChange={onChange}
        />
      ) : null}
      {spec ? <p className="text-caption text-muted-foreground">{spec.hint}</p> : null}
    </fieldset>
  );
}

interface ValueEditorProps {
  id: string;
  spec: RuleFieldSpec;
  node: DraftCondition;
  categories: Category[];
  categoriesError: boolean;
  onChange: (next: DraftCondition) => void;
}

function ValueEditor({ id, spec, node, categories, categoriesError, onChange }: ValueEditorProps) {
  const isList = isListOperator(node.operator);
  const set = (value: DraftCondition["value"]) => onChange({ ...node, value });

  if (spec.kind === "money") {
    return (
      <div className="space-y-2 sm:max-w-xs">
        <Label htmlFor={id}>Giá trị</Label>
        <MoneyInput
          id={id}
          value={typeof node.value === "number" ? node.value : undefined}
          onValueChange={(next) => set(next === undefined ? null : next)}
        />
      </div>
    );
  }

  if (spec.kind === "categories") {
    return (
      <CategoryChecklist
        categories={categories}
        loadFailed={categoriesError}
        selected={Array.isArray(node.value) ? node.value.map(String) : []}
        onChange={set}
      />
    );
  }

  if (spec.kind === "payment-method") {
    return (
      <div className="space-y-2 sm:max-w-xs">
        <Label htmlFor={id}>Giá trị</Label>
        <NativeSelect id={id} value={typeof node.value === "string" ? node.value : ""} onChange={(event) => set(event.target.value)}>
          {PAYMENT_METHOD_OPTIONS.map((option) => (
            <option key={option.value} value={option.value}>{option.label}</option>
          ))}
        </NativeSelect>
      </div>
    );
  }

  if (isList) {
    return (
      <TagInput
        id={id}
        label="Giá trị"
        placeholder={spec.kind === "uuid" ? "Dán UUID rồi nhấn Enter" : "Nhập giá trị rồi nhấn Enter"}
        values={Array.isArray(node.value) ? node.value.map(String) : []}
        onChange={set}
      />
    );
  }

  return (
    <div className="space-y-2 sm:max-w-md">
      <Label htmlFor={id}>Giá trị</Label>
      <Input
        id={id}
        value={typeof node.value === "string" ? node.value : ""}
        maxLength={255}
        autoComplete="off"
        placeholder={spec.kind === "uuid" ? "UUID người dùng" : undefined}
        onChange={(event) => set(event.target.value)}
      />
    </div>
  );
}

function CategoryChecklist({
  categories,
  loadFailed,
  selected,
  onChange,
}: {
  categories: Category[];
  loadFailed: boolean;
  selected: string[];
  onChange: (next: string[]) => void;
}) {
  const tree = useMemo(() => flattenCategoryTree(categories), [categories]);
  const known = new Set(tree.map(({ category }) => category.category_id));
  const orphans = selected.filter((id) => !known.has(id));

  function toggle(categoryId: string, checked: boolean) {
    onChange(checked ? [...selected, categoryId] : selected.filter((id) => id !== categoryId));
  }

  return (
    <fieldset className="space-y-2">
      <legend className="text-[14px] font-medium">Danh mục</legend>
      {loadFailed ? (
        <p role="alert" className="text-caption text-danger">
          Chưa tải được danh sách danh mục. Hãy tải lại trang rồi chọn.
        </p>
      ) : null}
      <div className="max-h-56 space-y-1 overflow-y-auto rounded-lg border border-border bg-surface-overlay p-2">
        {tree.length === 0 && orphans.length === 0 ? (
          <p className="px-2 py-3 text-caption text-muted-foreground">Chưa có danh mục nào.</p>
        ) : null}
        {tree.map(({ category, depth }) => {
          const checked = selected.includes(category.category_id);
          return (
            <label
              key={category.category_id}
              className="flex min-h-9 cursor-pointer items-center gap-3 rounded-md px-2 text-[14px] hover:bg-surface-2"
              style={{ paddingLeft: `${8 + depth * 16}px` }}
            >
              <Checkbox checked={checked} onCheckedChange={(next) => toggle(category.category_id, next === true)} />
              <span>{category.name}</span>
            </label>
          );
        })}
        {orphans.map((categoryId) => (
          <label key={categoryId} className="flex min-h-9 cursor-pointer items-center gap-3 rounded-md px-2 text-[14px] hover:bg-surface-2">
            <Checkbox checked onCheckedChange={() => toggle(categoryId, false)} />
            <span className="text-muted-foreground">Danh mục không còn tồn tại <span className="font-mono text-caption">{categoryId}</span></span>
          </label>
        ))}
      </div>
      <p className="text-caption text-muted-foreground">
        Đã chọn {selected.length} danh mục. Mỗi sản phẩm mang cả danh mục cấp cuối lẫn các danh mục cha, nên chọn danh mục cha cũng khớp sản phẩm con.
      </p>
    </fieldset>
  );
}

function TagInput({
  id,
  label,
  placeholder,
  values,
  onChange,
}: {
  id: string;
  label: string;
  placeholder: string;
  values: string[];
  onChange: (next: string[]) => void;
}) {
  const [draft, setDraft] = useState("");

  function commit(raw: string) {
    const parts = raw.split(/[\s,;]+/).map((part) => part.trim()).filter(Boolean);
    if (parts.length === 0) return;
    onChange([...values, ...parts.filter((part, at) => !values.includes(part) && parts.indexOf(part) === at)]);
    setDraft("");
  }

  return (
    <div className="space-y-2">
      <Label htmlFor={id}>{label}</Label>
      {values.length > 0 ? (
        <ul className="flex flex-wrap gap-2">
          {values.map((value) => (
            <li key={value}>
              <Badge variant="outline" className="gap-1.5 py-1 font-mono">
                {value}
                <button
                  type="button"
                  aria-label={`Bỏ ${value}`}
                  className="focus-ring -mr-1 grid size-5 place-content-center rounded-sm hover:bg-surface-3"
                  onClick={() => onChange(values.filter((item) => item !== value))}
                >
                  <X className="size-3" aria-hidden />
                </button>
              </Badge>
            </li>
          ))}
        </ul>
      ) : null}
      <Input
        id={id}
        value={draft}
        placeholder={placeholder}
        autoComplete="off"
        onChange={(event) => setDraft(event.target.value)}
        onBlur={() => commit(draft)}
        onKeyDown={(event) => {
          if (event.key === "Enter" || event.key === ",") {
            event.preventDefault();
            commit(draft);
          }
        }}
      />
    </div>
  );
}
