import { describe, expect, it } from "vitest";

import {
  applyFieldErrors,
  getProblem,
  isRetryable,
  parseRetryAfter,
  problemCode,
  problemMessage,
  retryAfterSeconds,
} from "@/lib/api/problem";

import { httpError, networkError, problem } from "./helpers";

describe("problemMessage", () => {
  it("dùng câu tiếng Việt theo code, không bao giờ trả detail thô", () => {
    const error = httpError(409, problem("SKU_CODE_ALREADY_EXISTS", 409));
    expect(problemMessage(error)).toBe("Mã SKU đã được dùng. Vui lòng chọn mã khác.");
    expect(problemMessage(error)).not.toContain("English");
  });

  it("rơi về câu theo status khi code chưa có trong bảng", () => {
    expect(problemMessage(httpError(412, problem("SOME_NEW_CODE", 412)))).toMatch(
      /người khác thay đổi/
    );
    expect(problemMessage(httpError(404, undefined))).toBe(
      "Không tìm thấy nội dung bạn yêu cầu."
    );
  });

  it("status 5xx lạ vẫn có câu chung của hệ thống", () => {
    expect(problemMessage(httpError(507, undefined))).toMatch(/phía hệ thống/);
  });

  it("báo mất kết nối khi request chưa tới server", () => {
    expect(problemMessage(networkError())).toMatch(/Không kết nối được/);
  });

  it("dùng fallback của caller với lỗi không phải HTTP", () => {
    expect(problemMessage(new Error("boom"), "Câu riêng")).toBe("Câu riêng");
  });
});

describe("getProblem", () => {
  it("đọc problem+json của gateway-auth như mọi service khác", () => {
    const error = httpError(401, problem("TOKEN_REVOKED", 401, { trace_id: "r1" }));
    expect(getProblem(error)).toMatchObject({ code: "TOKEN_REVOKED", status: 401, trace_id: "r1" });
    expect(problemCode(error)).toBe("TOKEN_REVOKED");
    expect(problemMessage(error)).toMatch(/bị thu hồi/);
  });

  it("trả null khi body không phải problem", () => {
    expect(getProblem(httpError(502, "<html>bad gateway</html>"))).toBeNull();
    // Hình dạng `{ error, message }` của plugin Traefik cũ không còn được hiểu.
    expect(getProblem(httpError(401, { error: "token_revoked", message: "x" }))).toBeNull();
  });
});

describe("isRetryable", () => {
  it("ưu tiên cờ retryable của backend", () => {
    expect(isRetryable(httpError(409, problem("X", 409, { retryable: true })))).toBe(true);
    expect(isRetryable(httpError(503, problem("X", 503, { retryable: false })))).toBe(false);
  });

  it("thiếu cờ thì suy từ status", () => {
    expect(isRetryable(httpError(503))).toBe(true);
    expect(isRetryable(httpError(429))).toBe(true);
    expect(isRetryable(httpError(400))).toBe(false);
    expect(isRetryable(httpError(409))).toBe(false);
  });

  it("mất mạng là thử lại được; lỗi không phải HTTP thì không", () => {
    expect(isRetryable(networkError())).toBe(true);
    expect(isRetryable(new Error("bug"))).toBe(false);
  });
});

describe("Retry-After", () => {
  it("đọc số giây từ header", () => {
    expect(retryAfterSeconds(httpError(429, undefined, { "retry-after": "7" }))).toBe(7);
  });

  it("bỏ qua giá trị không hợp lệ", () => {
    expect(parseRetryAfter("abc")).toBeNull();
    expect(parseRetryAfter("0")).toBeNull();
    expect(parseRetryAfter(undefined)).toBeNull();
    expect(parseRetryAfter(3)).toBe(3);
  });
});

describe("applyFieldErrors", () => {
  it("gắn lỗi vào field đã biết và trả về field lạ", () => {
    const error = httpError(
      400,
      problem("VALIDATION_FAILED", 400, {
        errors: [
          { field: "email", message: "bad email" },
          { field: "unknown", message: "?" },
        ],
      })
    );
    const set: Record<string, string> = {};
    const unmatched = applyFieldErrors(error, ["email", "password"] as const, (field, message) => {
      set[field] = message;
    });
    expect(set).toEqual({ email: "bad email" });
    expect(unmatched).toEqual(["unknown"]);
  });
});
