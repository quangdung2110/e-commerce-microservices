import { afterEach, describe, expect, it, vi } from "vitest";
vi.mock("server-only", () => ({}));
import { storefrontServer, publicFailure } from "@/lib/api/storefront-server";
import { serverGet, ServerApiError } from "@/lib/api/server";
import { problemMessage, problemTraceId } from "@/lib/api/problem";
afterEach(() => vi.unstubAllGlobals());

describe("public server boundary", () => {
  it("does not attach identity and repeats query tokens", async () => {
    const fetcher = vi.fn().mockResolvedValue(new Response(JSON.stringify({ items: [] })));
    vi.stubGlobal("fetch", fetcher);
    await serverGet("/api/v2/search/products", { query: { filter: ["a+b", "color:s:black"], in_stock: false }, revalidate: 0 });
    const [url, options] = fetcher.mock.calls[0];
    expect(new URL(url).searchParams.getAll("filter")).toEqual(["a+b", "color:s:black"]);
    expect(options.headers).not.toHaveProperty("Authorization");
    expect(options.headers).not.toHaveProperty("X-User-Id");
    expect(options.cache).toBe("no-store");
    expect(options.signal).toBeInstanceOf(AbortSignal);
  });
  it("never ISR-caches a degraded BFF response or signed review media", async () => {
    const fetcher = vi.fn().mockImplementation(async () => new Response(JSON.stringify({}), { headers: { "Cache-Control": "no-store" } }));
    vi.stubGlobal("fetch", fetcher);
    await storefrontServer.product("canonical-slug");
    await storefrontServer.reviews("p", { has_media: true, limit: 20 });
    expect(fetcher.mock.calls.every(([, options]) => options.cache === "no-store")).toBe(true);
  });
  it("treats only 404 as missing and preserves 503 as an error", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValueOnce(new Response("{}", { status: 404 })).mockResolvedValueOnce(new Response("{}", { status: 503 })));
    expect(await storefrontServer.product("missing")).toBeNull();
    await expect(storefrontServer.product("unavailable")).rejects.toMatchObject({ status: 503 });
  });
  it("serializes safe errors and maps them to Vietnamese with trace id", () => {
    const problem = { type: "about:blank", title: "Unavailable", status: 503, code: "PRODUCT_DETAIL_UNAVAILABLE", detail: "private backend detail", instance: "/api", trace_id: "trace-1", retryable: true };
    const failure = publicFailure(new ServerApiError(503, problem));
    expect(failure.problem?.detail).toBe("");
    expect(problemMessage(failure)).toContain("Thông tin sản phẩm");
    expect(problemMessage(failure)).not.toContain("private");
    expect(problemTraceId(failure)).toBe("trace-1");
  });
});
