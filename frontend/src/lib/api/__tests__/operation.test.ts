import { describe, expect, it } from "vitest";

import {
  PollAbortedError,
  PollTimeoutError,
  operationLocation,
  pollUntil,
  toPollRead,
  type PollRead,
} from "@/lib/api/operation";

interface Status {
  state: "PENDING" | "DONE";
}

/** Đồng hồ giả: `sleep` chỉ cộng thời gian và ghi lại độ dài mỗi lần chờ. */
function fakeClock() {
  let time = 0;
  const sleeps: number[] = [];
  return {
    sleeps,
    now: () => time,
    sleep: async (ms: number, signal: AbortSignal) => {
      if (signal.aborted) throw new PollAbortedError();
      sleeps.push(ms);
      time += ms;
    },
  };
}

function reader(responses: PollRead<Status>[]) {
  let index = 0;
  return async () => responses[Math.min(index++, responses.length - 1)];
}

const pending = (retryAfterSeconds: number | null = null): PollRead<Status> => ({
  data: { state: "PENDING" },
  retryAfterSeconds,
});
const done: PollRead<Status> = { data: { state: "DONE" }, retryAfterSeconds: null };
const isDone = (status: Status) => status.state === "DONE";

describe("pollUntil", () => {
  it("trả ngay khi lần đọc đầu đã xong, không chờ", async () => {
    const clock = fakeClock();
    const result = await pollUntil({ read: reader([done]), isDone, ...clock });
    expect(result.state).toBe("DONE");
    expect(clock.sleeps).toEqual([]);
  });

  it("tôn trọng Retry-After của server", async () => {
    const clock = fakeClock();
    await pollUntil({ read: reader([pending(3), pending(5), done]), isDone, ...clock });
    expect(clock.sleeps).toEqual([3000, 5000]);
  });

  it("tự giãn nhịp khi server không gợi ý, không vượt maxDelayMs", async () => {
    const clock = fakeClock();
    await pollUntil({
      read: reader([pending(), pending(), pending(), pending(), done]),
      isDone,
      initialDelayMs: 1000,
      maxDelayMs: 2000,
      ...clock,
    });
    expect(clock.sleeps).toEqual([1000, 1500, 2000, 2000]);
  });

  it("hết hạn thì ném PollTimeoutError kèm trạng thái cuối, không coi là thất bại", async () => {
    const clock = fakeClock();
    const error = await pollUntil({
      read: reader([pending(10)]),
      isDone,
      timeoutMs: 25_000,
      ...clock,
    }).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(PollTimeoutError);
    expect((error as PollTimeoutError<Status>).lastData).toEqual({ state: "PENDING" });
    // Lần chờ cuối bị cắt cho vừa hạn chót.
    expect(clock.sleeps).toEqual([10_000, 10_000, 5000]);
  });

  it("dừng khi bị huỷ từ bên ngoài", async () => {
    const controller = new AbortController();
    controller.abort();
    await expect(
      pollUntil({ read: reader([pending()]), isDone, signal: controller.signal })
    ).rejects.toBeInstanceOf(PollAbortedError);
  });

  it("dừng giữa chừng khi signal bị huỷ trong lúc chờ", async () => {
    const controller = new AbortController();
    const promise = pollUntil({
      read: reader([pending(60)]),
      isDone,
      signal: controller.signal,
    });
    controller.abort();
    await expect(promise).rejects.toBeInstanceOf(PollAbortedError);
  });
});

describe("đọc header", () => {
  it("lấy Location và Retry-After từ response", () => {
    const response = {
      data: { state: "PENDING" },
      headers: { location: "/api/v1/order-operations/op-1", "retry-after": "2" },
    };
    expect(operationLocation(response)).toBe("/api/v1/order-operations/op-1");
    expect(toPollRead(response)).toEqual({ data: { state: "PENDING" }, retryAfterSeconds: 2 });
  });
});
