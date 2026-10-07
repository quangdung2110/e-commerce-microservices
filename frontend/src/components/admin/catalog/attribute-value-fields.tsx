"use client";

import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { NativeSelect } from "@/components/ui/native-select";
import type {
  Attribute,
  AttributeValue,
  CategoryAttribute,
} from "@/lib/api/product";

interface AttributeValueFieldsProps {
  mappings: CategoryAttribute[];
  attributes: Attribute[];
  values: Record<string, AttributeValue | "">;
  onChange: (code: string, value: AttributeValue | "") => void;
  disabled?: boolean;
  emptyMessage: string;
}

export function AttributeValueFields({
  mappings,
  attributes,
  values,
  onChange,
  disabled = false,
  emptyMessage,
}: AttributeValueFieldsProps) {
  const byId = new Map(attributes.map((attribute) => [attribute.attribute_id, attribute]));

  if (mappings.length === 0) {
    return <p className="text-[14px] text-muted-foreground">{emptyMessage}</p>;
  }

  return (
    <div className="grid gap-5 sm:grid-cols-2">
      {mappings.map((mapping) => {
        const id = `catalog-attribute-${mapping.attribute_id}`;
        const attribute = byId.get(mapping.attribute_id);
        const value = values[mapping.attribute_code] ?? "";

        if (mapping.value_type === "BOOLEAN") {
          return (
            <div key={mapping.attribute_id} className="space-y-2">
              <Label htmlFor={id}>
                {mapping.attribute_name}{mapping.is_required ? " *" : ""}
              </Label>
              <NativeSelect
                id={id}
                value={value === "" ? "" : String(value)}
                required={mapping.is_required}
                disabled={disabled}
                onChange={(event) =>
                  onChange(
                    mapping.attribute_code,
                    event.target.value === "" ? "" : event.target.value === "true"
                  )
                }
              >
                <option value="">Chọn giá trị</option>
                <option value="true">Có</option>
                <option value="false">Không</option>
              </NativeSelect>
            </div>
          );
        }

        return (
          <div key={mapping.attribute_id} className="space-y-2">
            <Label htmlFor={id}>
              {mapping.attribute_name}{mapping.is_required ? " *" : ""}
            </Label>
            {mapping.value_type === "SELECT" ? (
              <NativeSelect
                id={id}
                value={String(value)}
                required={mapping.is_required}
                disabled={disabled}
                onChange={(event) => onChange(mapping.attribute_code, event.target.value)}
              >
                <option value="">Chọn {mapping.attribute_name.toLowerCase()}</option>
                {(attribute?.options ?? [])
                  .slice()
                  .sort((left, right) => left.sort_order - right.sort_order)
                  .map((option) => (
                    <option key={option.option_id} value={option.code}>
                      {option.label}
                    </option>
                  ))}
              </NativeSelect>
            ) : (
              <Input
                id={id}
                type={mapping.value_type === "NUMBER" ? "number" : "text"}
                inputMode={mapping.value_type === "NUMBER" ? "decimal" : undefined}
                value={String(value)}
                required={mapping.is_required}
                disabled={disabled}
                onChange={(event) => onChange(mapping.attribute_code, event.target.value)}
              />
            )}
            <p className="text-caption text-muted-foreground">
              Mã thuộc tính: <span className="font-mono">{mapping.attribute_code}</span>
            </p>
          </div>
        );
      })}
    </div>
  );
}
