import { describe, expect, it } from "vitest";
import { ALL_ORDER_HISTORY_FROM, buyerOrderQuery, orderDateTimeInput, orderDateTimeIso, parseBuyerOrderSearch } from "@/lib/buyer-order-filters";

describe("Buyer order search", () => {
  it("keeps the default window unless the buyer explicitly requests all history", () => {
    expect(parseBuyerOrderSearch({}).filters).toEqual({});
    expect(parseBuyerOrderSearch({ history: "all" }).filters).toEqual({ created_from: ALL_ORDER_HISTORY_FROM });
  });
  it("round trips all filters, repeated statuses, zero amounts and all-history through URLs", () => {
    const parsed = parseBuyerOrderSearch({ history: "all", tab: "TO_PAY", q: "Áo", order_status: ["PENDING_PAYMENT", "CANCELLING"], payment_status: ["PENDING", "FAILED"], min_amount_minor: "0", max_amount_minor: "1000000" });
    expect(parsed.errors).toEqual([]);
    const query = buyerOrderQuery(parsed.tab, parsed.q, parsed.filters, parsed.allHistory);
    expect(query.getAll("order_status")).toEqual(["PENDING_PAYMENT", "CANCELLING"]);
    expect(query.get("history")).toBe("all");
    expect(query.has("created_from")).toBe(false);
    expect(parsed.filters.min_amount_minor).toBe(0);
  });
  it.each([
    { created_from: "2026-02-30T00:00:00Z" },
    { created_from: "wrong" },
    { created_from: "2026-10-20T00:00:00Z", created_to: "2026-10-19T00:00:00Z" },
    { min_amount_minor: "-1" }, { min_amount_minor: "1.5" },
    { max_amount_minor: "9007199254740992" }, { min_amount_minor: "2", max_amount_minor: "1" },
    { order_status: "UNKNOWN" }, { payment_status: ["PAID", "PAID"] },
  ])("rejects invalid filters without silently broadening them: %j", raw => {
    expect(parseBuyerOrderSearch(raw).errors.length).toBeGreaterThan(0);
  });
  it("keeps an explicit range when history=all also appears and converts Vietnamese local input", () => {
    const from = orderDateTimeIso("2025-01-01T00:00");
    expect(from).toBe("2024-12-31T17:00:00.000Z");
    expect(orderDateTimeInput(from)).toBe("2025-01-01T00:00");
    const parsed = parseBuyerOrderSearch({ history: "all", created_from: from });
    expect(parsed.allHistory).toBe(false);
    expect(parsed.filters.created_from).toBe(from);
  });
});
