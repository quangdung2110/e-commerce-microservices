"use client";
import { useState, useEffect, useRef, useId } from "react";
import { useRouter } from "next/navigation";
import { Search } from "lucide-react";
import { Input } from "@/components/ui/input";
import { useQuery } from "@tanstack/react-query";
import { searchApi } from "@/lib/api/search";

export function SearchOmnibox() {
  const [query, setQuery] = useState("");
  const [debounced, setDebounced] = useState("");
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(-1);
  const ref = useRef<HTMLDivElement>(null);
  const listId = useId();
  const router = useRouter();
  useEffect(() => { const timer = setTimeout(() => setDebounced(query.trim()), 250); return () => clearTimeout(timer); }, [query]);
  const result = useQuery({ queryKey: ["search-suggestions", debounced], queryFn: ({ signal }) => searchApi.suggestions(debounced, signal), enabled: debounced.length >= 2, retry: false, staleTime: 30_000 });
  const items = debounced === query.trim() && !result.isError ? result.data?.items ?? [] : [];
  const expanded = open && items.length > 0;
  useEffect(() => { const close = (event: MouseEvent) => { if (!ref.current?.contains(event.target as Node)) setOpen(false); }; document.addEventListener("mousedown", close); return () => document.removeEventListener("mousedown", close); }, []);
  const submit = (text: string) => { setOpen(false); setActive(-1); const q = text.trim(); if (q) router.push(`/search?q=${encodeURIComponent(q)}`); };
  return <div ref={ref} className="relative mx-auto w-full max-w-2xl">
    <form onSubmit={(event) => { event.preventDefault(); submit(active >= 0 && items[active] ? items[active].text : query); }} role="search">
      <label htmlFor={`${listId}-input`} className="sr-only">Tìm kiếm sản phẩm</label>
      <div className="relative"><Input id={`${listId}-input`} role="combobox" aria-autocomplete="list" aria-expanded={expanded} aria-controls={expanded ? listId : undefined} aria-activedescendant={expanded && active >= 0 ? `${listId}-${active}` : undefined} autoComplete="off" maxLength={256} placeholder="Tìm sản phẩm…" className="pr-12" value={query} onChange={(event) => { setQuery(event.target.value); setOpen(true); setActive(-1); }} onFocus={() => setOpen(true)} onBlur={(event) => { if (!ref.current?.contains(event.relatedTarget)) setOpen(false); }} onKeyDown={(event) => {
        if (event.key === "Escape") { event.preventDefault(); setOpen(false); setActive(-1); }
        if (expanded && event.key === "ArrowDown") { event.preventDefault(); setActive((value) => (value + 1) % items.length); }
        if (expanded && event.key === "ArrowUp") { event.preventDefault(); setActive((value) => (value <= 0 ? items.length - 1 : value - 1)); }
      }} /><button type="submit" aria-label="Tìm kiếm" className="focus-ring absolute inset-y-0 right-0 flex w-11 items-center justify-center rounded-lg text-muted-foreground"><Search className="size-5" aria-hidden /></button></div>
    </form>
    {expanded && <ul id={listId} role="listbox" aria-label="Từ khóa gợi ý" className="absolute top-full z-50 mt-2 w-full rounded-xl border border-border bg-surface-overlay p-2 shadow-md">{items.map((item, index) => <li key={`${item.text}-${index}`} id={`${listId}-${index}`} role="option" aria-selected={index === active} className={`flex min-h-11 cursor-pointer items-center rounded-lg px-3 text-[14px] hover:bg-accent ${index === active ? "bg-accent" : ""}`} onMouseDown={(event) => event.preventDefault()} onClick={() => submit(item.text)}>{item.text}</li>)}</ul>}
  </div>;
}
