"use client";
import { useId, useState } from "react";
import { ChevronDown } from "lucide-react";
import type { FacetSet } from "@/lib/api/search";
import { Input } from "@/components/ui/input";

type Facet = FacetSet["filters"][number];
const PREVIEW_OPTIONS = 6;
const fold = (value: string) => value.toLocaleLowerCase("vi").normalize("NFD").replace(/[\u0300-\u036f]/g, "").replace(/đ/g, "d");

export function FacetGroup({ facet, selected, onChange, initiallyOpen, disabled }: {
  facet: Facet; selected: Set<string>; onChange: (token: string, checked: boolean) => void; initiallyOpen: boolean; disabled: boolean;
}) {
  const id = useId();
  const selectedCount = facet.options.filter(option => selected.has(option.value)).length;
  const [open, setOpen] = useState(initiallyOpen || selectedCount > 0);
  const [expanded, setExpanded] = useState(false);
  const [query, setQuery] = useState("");
  const matching = facet.options.filter(option => selected.has(option.value) || fold(option.label).includes(fold(query)));
  const chosen = matching.filter(option => selected.has(option.value));
  const remaining = matching.filter(option => !selected.has(option.value));
  const visible = [...chosen, ...(expanded || query ? remaining : remaining.slice(0, PREVIEW_OPTIONS))];

  return <fieldset className="min-w-0 border-b border-border pb-3">
    <legend className="w-full"><button type="button" aria-expanded={open} aria-controls={id} onClick={() => setOpen(!open)} className="focus-ring flex min-h-11 w-full items-center justify-between gap-3 rounded-lg text-left font-medium">
      <span>{facet.label}{selectedCount > 0 && <span className="ml-2 text-caption text-muted-foreground"> Đã chọn {selectedCount}</span>}</span><ChevronDown className={`size-4 shrink-0 transition-transform ${open ? "rotate-180" : ""}`} aria-hidden />
    </button></legend>
    {open && <div id={id} className="space-y-2">
      {facet.options.length > PREVIEW_OPTIONS && <Input type="search" aria-label={`Tìm trong ${facet.label}`} placeholder={`Tìm ${facet.label.toLocaleLowerCase("vi")}…`} value={query} onChange={event => setQuery(event.target.value)} onKeyDown={event => { if (event.key === "Enter") event.preventDefault(); }} />}
      <div className={expanded || query ? "max-h-64 overflow-y-auto pr-1" : undefined}>
        {visible.map(option => <label key={option.value} className="flex min-h-11 cursor-pointer items-center gap-3 text-[14px]">
          <input type="checkbox" value={option.value} checked={selected.has(option.value)} disabled={disabled} onChange={event => onChange(option.value, event.target.checked)} className="focus-ring size-4 shrink-0 accent-clay" />
          <span className="min-w-0 break-words">{option.label} <span className="text-caption text-muted-foreground">(≈{option.count.toLocaleString("vi-VN")})</span></span>
        </label>)}
        {!visible.length && <p role="status" className="py-3 text-caption text-muted-foreground">Không có lựa chọn khớp từ khóa.</p>}
      </div>
      {!query && remaining.length > PREVIEW_OPTIONS && <button type="button" onClick={() => setExpanded(!expanded)} className="focus-ring min-h-11 rounded-lg text-[14px] underline underline-offset-4">{expanded ? "Thu gọn" : `Xem thêm ${remaining.length - PREVIEW_OPTIONS} lựa chọn`}</button>}
    </div>}
  </fieldset>;
}
