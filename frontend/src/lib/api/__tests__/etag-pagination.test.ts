import { describe, expect, it } from "vitest";

import { idempotencyHeaders, inventoryCommandHeaders, newCommandId } from "@/lib/api/command";
import { ifMatchHeaders, isStaleVersion, readEtag } from "@/lib/api/etag";
import {
  FIRST_PAGE,
  cursorPagerReducer,
  nextCursorOf,
  pageNumber,
} from "@/lib/api/pagination";

import { httpError, problem } from "./helpers";

describe("ETag", () => {
  it("đọc ETag nguyên văn, kể cả dấu nháy", () => {
    expect(readEtag({ headers: { etag: '"catalog-revision-42"' } })).toBe('"catalog-revision-42"');
    expect(readEtag({ headers: {} })).toBeNull();
    expect(ifMatchHeaders('"7"')).toEqual({ "If-Match": '"7"' });
  });

  it("412 là ETag cũ, bất kể code của service", () => {
    expect(isStaleVersion(httpError(412, problem("PRODUCT_REVISION_MISMATCH", 412)))).toBe(true);
    expect(isStaleVersion(httpError(412, problem("CAMPAIGN_REVISION_MISMATCH", 412)))).toBe(true);
    expect(isStaleVersion(httpError(409, problem("INVALID_STATE_TRANSITION", 409)))).toBe(false);
  });
});

describe("command headers", () => {
  it("command ID đủ dài cho Inventory (≥ 16 ký tự) và không trùng", () => {
    const a = newCommandId();
    expect(a.length).toBeGreaterThanOrEqual(16);
    expect(newCommandId()).not.toBe(a);
  });

  it("Inventory: causation là command ID, correlation mặc định cũng vậy", () => {
    expect(inventoryCommandHeaders("cmd-0000000000001")).toEqual({
      "Idempotency-Key": "cmd-0000000000001",
      "X-Correlation-ID": "cmd-0000000000001",
      "X-Causation-ID": "cmd-0000000000001",
    });
    expect(inventoryCommandHeaders("cmd-0000000000001", "order-10001")["X-Correlation-ID"]).toBe(
      "order-10001"
    );
    expect(idempotencyHeaders("k")).toEqual({ "Idempotency-Key": "k" });
  });
});

describe("cursor pager", () => {
  it("đi tiếp rồi quay lại đúng trang trước", () => {
    let state = FIRST_PAGE;
    state = cursorPagerReducer(state, { type: "next", cursor: "c2" });
    state = cursorPagerReducer(state, { type: "next", cursor: "c3" });
    expect(state).toEqual({ current: "c3", history: [null, "c2"] });
    expect(pageNumber(state)).toBe(3);

    state = cursorPagerReducer(state, { type: "previous" });
    expect(state).toEqual({ current: "c2", history: [null] });
    state = cursorPagerReducer(state, { type: "previous" });
    expect(state).toEqual(FIRST_PAGE);
    expect(cursorPagerReducer(state, { type: "previous" })).toEqual(FIRST_PAGE);
  });

  it("next_cursor vắng mặt hoặc null nghĩa là hết trang", () => {
    expect(nextCursorOf({ next_cursor: "abc" })).toBe("abc");
    expect(nextCursorOf({})).toBeUndefined();
    expect(nextCursorOf({ next_cursor: null })).toBeUndefined();
  });
});
