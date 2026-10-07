"use client";

import { useMemo } from "react";

import { Input } from "@/components/ui/input";
import { DISPLAY_TIME_ZONE } from "@/lib/format";

const inputFormatter = new Intl.DateTimeFormat("en-CA", {
  timeZone: DISPLAY_TIME_ZONE,
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
  hour: "2-digit",
  minute: "2-digit",
  hourCycle: "h23",
});

export function vietnamIsoToLocalInput(iso?: string): string {
  if (!iso) return "";
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return "";
  const parts = Object.fromEntries(
    inputFormatter
      .formatToParts(date)
      .filter((part) => part.type !== "literal")
      .map((part) => [part.type, part.value])
  );
  return `${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}`;
}

export function localInputToVietnamIso(value: string): string | undefined {
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(value)) return undefined;
  const date = new Date(`${value}:00+07:00`);
  return Number.isNaN(date.getTime()) ? undefined : date.toISOString();
}

interface DateTimeInputProps
  extends Omit<React.ComponentProps<typeof Input>, "type" | "value" | "onChange"> {
  value?: string;
  onValueChange?: (value: string | undefined) => void;
}

export function DateTimeInput({ value, onValueChange, ...props }: DateTimeInputProps) {
  const localValue = useMemo(() => vietnamIsoToLocalInput(value), [value]);
  return (
    <Input
      {...props}
      type="datetime-local"
      value={localValue}
      onChange={(event) => onValueChange?.(localInputToVietnamIso(event.target.value))}
    />
  );
}
