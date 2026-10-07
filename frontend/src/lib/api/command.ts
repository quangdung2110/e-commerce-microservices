import { v4 as uuidv4 } from "uuid";

/**
 * Định danh cho lệnh có side effect.
 *
 * Quy tắc chung (`contracts/conventions/idempotency.md`): key do caller sinh và
 * PHẢI được dùng lại qua mọi lần retry của **cùng một** ý định của người dùng.
 * Sinh key mới mỗi lần bấm nút là mở đường cho việc tạo đơn / hoàn tiền hai lần
 * khi request đầu thực ra đã tới server. Việc giữ hay đổi key do `useCommand`
 * quyết định; các hàm ở đây chỉ dựng header.
 */

/** Command ID mới — UUIDv4, đủ dài cho mọi contract (Inventory yêu cầu ≥ 16 ký tự). */
export function newCommandId(): string {
  return uuidv4();
}

/** Header cho Cart, Checkout preview, Order và các contract dùng `Idempotency-Key`. */
export function idempotencyHeaders(commandId: string): Record<string, string> {
  return { "Idempotency-Key": commandId };
}

/**
 * Header cho Inventory ADMIN API: ngoài `Idempotency-Key` còn bắt buộc
 * `X-Correlation-ID` và `X-Causation-ID`. Với lệnh khởi đầu từ admin, causation
 * là chính command ID; correlation mặc định cũng vậy trừ khi lệnh thuộc một
 * luồng đã có correlation (ví dụ xuất kho cho một đơn cụ thể).
 */
export function inventoryCommandHeaders(
  commandId: string,
  correlationId: string = commandId
): Record<string, string> {
  return {
    "Idempotency-Key": commandId,
    "X-Correlation-ID": correlationId,
    "X-Causation-ID": commandId,
  };
}
