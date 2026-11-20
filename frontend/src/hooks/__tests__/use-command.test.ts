// @vitest-environment jsdom
import { createElement, type ReactNode } from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, renderHook } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { useCommand } from "@/hooks/use-command";
import { httpError, networkError, problem } from "@/lib/api/__tests__/helpers";

function wrapper({ children }: { children: ReactNode }) {
  const client = new QueryClient({ defaultOptions: { mutations: { retry: false } } });
  return createElement(QueryClientProvider, { client }, children);
}

/** Hook mà mỗi lần gửi lấy kết quả kế tiếp trong `outcomes`, ghi lại command ID. */
function setup(outcomes: unknown[]) {
  const ids: string[] = [];
  let call = 0;
  const { result } = renderHook(
    () =>
      useCommand<string, void>({
        mutationFn: async (_vars, { commandId }) => {
          ids.push(commandId);
          const outcome = outcomes[call++];
          if (outcome instanceof Error) throw outcome;
          return "ok";
        },
      }),
    { wrapper }
  );
  const send = () =>
    act(async () => {
      await result.current.mutateAsync().catch(() => undefined);
    });
  return { ids, result, send };
}

describe("useCommand", () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it("dùng lại command ID sau lỗi có thể thử lại (mất mạng, 503)", async () => {
    const { ids, send } = setup([networkError(), httpError(503), "ok"]);
    await send();
    await send();
    await send();
    expect(new Set(ids).size).toBe(1);
  });

  it("sinh ID mới sau lỗi không thể thử lại — người dùng đã sửa dữ liệu", async () => {
    const { ids, send } = setup([httpError(422, problem("INVALID_REFUND_SCOPE", 422)), "ok"]);
    await send();
    await send();
    expect(ids[0]).not.toBe(ids[1]);
  });

  it("sinh ID mới cho ý định tiếp theo sau khi thành công", async () => {
    const { ids, send } = setup(["ok", "ok"]);
    await send();
    await send();
    expect(ids[0]).not.toBe(ids[1]);
  });

  it("giữ command ID khi lệnh đã được nhận nhưng kết quả cuối chưa rõ (isOutcomeUnresolved)", async () => {
    const ids: string[] = [];
    const { result } = renderHook(
      () =>
        useCommand<{ unresolved: boolean }, { unresolved: boolean }>({
          isOutcomeUnresolved: (data) => data.unresolved,
          mutationFn: async (vars, { commandId }) => {
            ids.push(commandId);
            return vars;
          },
        }),
      { wrapper }
    );
    const send = (unresolved: boolean) =>
      act(async () => {
        await result.current.mutateAsync({ unresolved });
      });

    await send(true); // nhận lệnh, poll hết hạn: chưa rõ
    await send(true); // vẫn chưa rõ → replay cùng ý định
    await send(false); // kết thúc rõ ràng → vẫn là replay của ý định đó
    await send(false); // ý định mới sau khi đã có kết quả cuối
    expect(ids[1]).toBe(ids[0]);
    expect(ids[2]).toBe(ids[0]);
    expect(ids[3]).not.toBe(ids[0]);
  });

  it("startNewIntent bỏ ID đang giữ dù lần trước lỗi mạng", async () => {
    const { ids, send, result } = setup([networkError(), "ok"]);
    await send();
    act(() => result.current.startNewIntent());
    await send();
    expect(ids[0]).not.toBe(ids[1]);
  });

  it("tôn trọng Retry-After trước khi cho phép gửi lại đúng lệnh", async () => {
    vi.useFakeTimers();
    const unavailable = httpError(
      503,
      problem("INVENTORY_UNAVAILABLE", 503, { retryable: true }),
      { "retry-after": "2" }
    );
    const { result, send } = setup([unavailable]);

    await send();
    expect(result.current.retryDelaySeconds).toBe(2);
    expect(result.current.isRetryDelayed).toBe(true);

    act(() => vi.advanceTimersByTime(1000));
    expect(result.current.retryDelaySeconds).toBe(1);

    act(() => vi.advanceTimersByTime(1000));
    expect(result.current.retryDelaySeconds).toBe(0);
    expect(result.current.isRetryDelayed).toBe(false);
  });

  it("startNewIntent xóa thời gian chờ Retry-After khi người dùng sửa payload", async () => {
    vi.useFakeTimers();
    const unavailable = httpError(
      503,
      problem("INVENTORY_UNAVAILABLE", 503, { retryable: true }),
      { "retry-after": "5" }
    );
    const { result, send } = setup([unavailable]);

    await send();
    act(() => result.current.startNewIntent());

    expect(result.current.retryDelaySeconds).toBe(0);
    expect(result.current.isRetryDelayed).toBe(false);
  });
});
