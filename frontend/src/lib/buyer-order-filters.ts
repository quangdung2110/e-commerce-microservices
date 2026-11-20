import type { BuyerTab, OrderListParams } from "@/lib/api/order";
import { BUYER_TAB_LABELS, ORDER_STATUS_LABELS, PAYMENT_STATUS_LABELS } from "@/lib/labels/order";

export type BuyerOrderFilters = Omit<OrderListParams, "tab" | "q" | "cursor" | "limit">;
export type OrderSearchValues = Record<string, string | string[] | undefined>;
export const ALL_ORDER_HISTORY_FROM = "1970-01-01T00:00:00.000Z";
const first = (value: string | string[] | undefined) => Array.isArray(value) ? value[0] : value;
function validInstant(value: string): boolean {
  const match = /^(\d{4})-(\d{2})-(\d{2})T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/.exec(value);
  if (!match || !Number.isFinite(Date.parse(value))) return false;
  const year = Number(match[1]), month = Number(match[2]), day = Number(match[3]);
  const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
  const days = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
  return month >= 1 && month <= 12 && day >= 1 && day <= days[month - 1];
}

export function parseBuyerOrderSearch(raw: OrderSearchValues) {
  const errors: string[] = [];
  const filters: BuyerOrderFilters = {};
  const rawTab = first(raw.tab);
  const tab = rawTab && Object.hasOwn(BUYER_TAB_LABELS, rawTab) ? rawTab as BuyerTab : undefined;
  if (rawTab && !tab) errors.push("Trạng thái nhóm đơn hàng không hợp lệ.");
  const q = first(raw.q)?.trim() ?? "";
  if (q.length > 200) errors.push("Từ khóa tối đa 200 ký tự.");
  for (const [name, label] of [["created_from", "Từ ngày"], ["created_to", "Đến ngày"]] as const) {
    const value = first(raw[name]);
    if (!value) continue;
    if (!validInstant(value)) {
      errors.push(`${label} không hợp lệ.`);
    } else filters[name] = value;
  }
  if (raw.history && first(raw.history) !== "all") errors.push("Khoảng thời gian không hợp lệ.");
  if (first(raw.history) === "all" && !first(raw.created_from) && !first(raw.created_to)) filters.created_from = ALL_ORDER_HISTORY_FROM;
  if (filters.created_from && filters.created_to && Date.parse(filters.created_from) > Date.parse(filters.created_to)) errors.push("Đến ngày phải sau hoặc bằng từ ngày.");
  for (const [name, labels, max] of [["order_status", ORDER_STATUS_LABELS, 10], ["payment_status", PAYMENT_STATUS_LABELS, 6]] as const) {
    const values = typeof raw[name] === "string" ? [raw[name]] : raw[name] ?? [];
    if (values.length > max || new Set(values).size !== values.length || values.some(value => !Object.hasOwn(labels, value))) errors.push("Bộ lọc trạng thái không hợp lệ.");
    else if (values.length) Object.assign(filters, { [name]: values });
  }
  for (const [name, label] of [["min_amount_minor", "Số tiền tối thiểu"], ["max_amount_minor", "Số tiền tối đa"]] as const) {
    const value = first(raw[name]);
    if (!value) continue;
    const amount = Number(value);
    if (!/^\d+$/.test(value) || !Number.isSafeInteger(amount)) errors.push(`${label} phải là số nguyên không âm hợp lệ.`);
    else filters[name] = amount;
  }
  if (filters.min_amount_minor !== undefined && filters.max_amount_minor !== undefined && filters.min_amount_minor > filters.max_amount_minor) errors.push("Số tiền tối đa phải lớn hơn hoặc bằng số tiền tối thiểu.");
  return { tab, q, filters, errors, allHistory: first(raw.history) === "all" && !first(raw.created_from) && !first(raw.created_to) };
}

export function buyerOrderQuery(tab: BuyerTab | undefined, q: string, filters: BuyerOrderFilters, allHistory = false): URLSearchParams {
  const query = new URLSearchParams();
  if (tab) query.set("tab", tab);
  if (q.trim()) query.set("q", q.trim());
  for (const [key, value] of Object.entries(filters)) {
    if (allHistory && key === "created_from" && value === ALL_ORDER_HISTORY_FROM) continue;
    for (const item of Array.isArray(value) ? value : [value]) if (item !== undefined && item !== "") query.append(key, String(item));
  }
  if (allHistory) query.set("history", "all");
  return query;
}

const vietnamDateTime = new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Ho_Chi_Minh", year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hourCycle: "h23" });
export function orderDateTimeInput(iso?: string): string {
  if (!iso || !Number.isFinite(Date.parse(iso))) return "";
  const parts = Object.fromEntries(vietnamDateTime.formatToParts(new Date(iso)).map(part => [part.type, part.value]));
  return `${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}`;
}
export function orderDateTimeIso(value: string): string | undefined {
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(value)) return undefined;
  const date = new Date(`${value}:00+07:00`);
  return Number.isFinite(date.getTime()) ? date.toISOString() : undefined;
}
