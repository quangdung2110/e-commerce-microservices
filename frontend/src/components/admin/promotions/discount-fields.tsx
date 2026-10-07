"use client";

import { MoneyInput } from "@/components/admin/money-input";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { NativeSelect } from "@/components/ui/native-select";
import {
  DISCOUNT_TYPE_LABELS,
  type DiscountDraft,
  type DiscountType,
} from "@/lib/promotion/discount";

interface DiscountFieldsProps {
  idPrefix: string;
  value: DiscountDraft;
  onChange: (next: DiscountDraft) => void;
  disabled?: boolean;
  error?: string;
}

export function DiscountFields({ idPrefix, value, onChange, disabled, error }: DiscountFieldsProps) {
  const errorId = `${idPrefix}-error`;
  const describedBy = error ? errorId : undefined;

  return (
    <div className="space-y-4">
      <div className="grid gap-5 sm:grid-cols-2">
        <div className="space-y-2">
          <Label htmlFor={`${idPrefix}-type`}>Kiểu giảm *</Label>
          <NativeSelect
            id={`${idPrefix}-type`}
            value={value.type}
            disabled={disabled}
            onChange={(event) => onChange({ ...value, type: event.target.value as DiscountType })}
          >
            {(Object.keys(DISCOUNT_TYPE_LABELS) as DiscountType[]).map((type) => (
              <option key={type} value={type}>{DISCOUNT_TYPE_LABELS[type]}</option>
            ))}
          </NativeSelect>
        </div>

        {value.type === "PERCENTAGE" ? (
          <div className="space-y-2">
            <Label htmlFor={`${idPrefix}-percent`}>Phần trăm giảm *</Label>
            <div className="relative">
              <Input
                id={`${idPrefix}-percent`}
                value={value.percent}
                inputMode="decimal"
                disabled={disabled}
                aria-invalid={error ? true : undefined}
                aria-describedby={describedBy}
                className="pr-10 tabular-nums"
                placeholder="10"
                onChange={(event) => onChange({ ...value, percent: event.target.value })}
              />
              <span className="pointer-events-none absolute right-3.5 top-1/2 -translate-y-1/2 text-caption text-muted-foreground">%</span>
            </div>
          </div>
        ) : (
          <div className="space-y-2">
            <Label htmlFor={`${idPrefix}-fixed`}>Số tiền giảm *</Label>
            <MoneyInput
              id={`${idPrefix}-fixed`}
              value={value.fixed}
              disabled={disabled}
              aria-invalid={error ? true : undefined}
              aria-describedby={describedBy}
              onValueChange={(fixed) => onChange({ ...value, fixed })}
            />
          </div>
        )}
      </div>

      {value.type === "PERCENTAGE" ? (
        <div className="space-y-2 sm:max-w-[calc(50%-10px)]">
          <Label htmlFor={`${idPrefix}-cap`}>Giảm tối đa</Label>
          <MoneyInput
            id={`${idPrefix}-cap`}
            value={value.maxDiscount}
            disabled={disabled}
            onValueChange={(maxDiscount) => onChange({ ...value, maxDiscount })}
          />
          <p className="text-caption text-muted-foreground">Để trống nếu không giới hạn mức giảm.</p>
        </div>
      ) : null}

      {error ? (
        <p id={errorId} role="alert" className="text-caption text-danger">{error}</p>
      ) : null}
    </div>
  );
}
