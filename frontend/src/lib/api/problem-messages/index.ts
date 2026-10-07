import { CHECKOUT_MESSAGES, CART_MESSAGES, INVENTORY_MESSAGES, ORDER_MESSAGES } from "./commerce";
import { COMMON_MESSAGES, STATUS_MESSAGES } from "./common";
import { PRODUCT_MESSAGES, SEARCH_MESSAGES } from "./catalog";
import { PROMOTION_MESSAGES, REVIEW_MESSAGES } from "./engagement";
import { AUTH_MESSAGES, USER_MESSAGES } from "./identity";

/**
 * Câu tiếng Việt cho từng mã lỗi `code` của backend.
 *
 * Nguyên tắc viết: nói người dùng *phải làm gì*, không chỉ *sai cái gì*. Không
 * đổ lỗi cho người dùng, không dùng thuật ngữ kỹ thuật, không nhắc tên service.
 *
 * Mã trùng tên giữa các service (ví dụ `ADDRESS_NOT_FOUND`) phải mang cùng một
 * nghĩa; bảng gộp theo thứ tự dưới đây, bảng sau ghi đè bảng trước.
 */
export const PROBLEM_MESSAGES: Readonly<Record<string, string>> = {
  ...COMMON_MESSAGES,
  ...AUTH_MESSAGES,
  ...USER_MESSAGES,
  ...PRODUCT_MESSAGES,
  ...SEARCH_MESSAGES,
  ...CART_MESSAGES,
  ...CHECKOUT_MESSAGES,
  ...ORDER_MESSAGES,
  ...INVENTORY_MESSAGES,
  ...PROMOTION_MESSAGES,
  ...REVIEW_MESSAGES,
};

export { STATUS_MESSAGES };
