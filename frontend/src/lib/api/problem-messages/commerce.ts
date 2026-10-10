/**
 * Cart, Checkout, Order, Inventory. Mã Checkout lấy từ enum `CheckoutProblemCode`
 * của Checkout OpenAPI (bảng ở `docs/checkout/interfaces.md#error-catalog`); các
 * service còn lại lấy từ bảng lỗi miền trong `docs/<service>/domain.md` vì contract
 * của chúng không liệt kê `code`. Mã Checkout relay từ Order (ví dụ
 * `INVENTORY_RESERVATION_REJECTED`) nằm ở `ORDER_MESSAGES`; `ADDRESS_NOT_FOUND`
 * dùng chung câu của User.
 */
export const CART_MESSAGES: Record<string, string> = {
  CART_FULL: "Giỏ hàng đã đủ số sản phẩm tối đa. Hãy bớt sản phẩm khác trước khi thêm.",
  PRODUCT_NOT_AVAILABLE: "Sản phẩm này hiện không còn bán.",
  PRODUCT_SNAPSHOT_REQUIRED: "Thông tin sản phẩm đang được cập nhật. Vui lòng thử lại.",
  DELTA_INVALID: "Số lượng chưa hợp lệ.",
  QUANTITY_INVALID: "Số lượng phải từ 1 đến 100. Dùng nút xóa để bỏ sản phẩm khỏi giỏ.",
  ITEM_NOT_FOUND: "Sản phẩm này đã được xóa ở nơi khác. Hãy tải lại giỏ hàng.",
  CART_STORE_UNAVAILABLE: "Giỏ hàng đang gián đoạn. Hãy thử lại sau giây lát.",
  PRODUCT_UNAVAILABLE: "Chưa xác minh được sản phẩm. Hãy thử lại sau giây lát.",
};

export const CHECKOUT_MESSAGES: Record<string, string> = {
  CART_EMPTY: "Giỏ hàng đang trống.",
  CART_CHANGED: "Giỏ hàng vừa thay đổi. Vui lòng xem lại đơn hàng.",
  SKU_UNAVAILABLE: "Một số sản phẩm không còn bán. Vui lòng xem lại giỏ hàng.",
  PRICE_CHANGED: "Giá một số sản phẩm vừa thay đổi. Vui lòng xem lại đơn hàng.",
  INSUFFICIENT_STOCK: "Một số sản phẩm không đủ hàng. Vui lòng giảm số lượng hoặc bỏ bớt.",
  PROMOTION_INVALID: "Voucher không còn áp dụng được cho đơn này.",
  PROMOTION_CHANGED: "Voucher vừa thay đổi. Vui lòng xem lại đơn hàng.",
  FINAL_AMOUNT_NOT_PAYABLE: "Số tiền sau giảm giá phải từ 1 ₫. Vui lòng chọn voucher khác.",
  CHECKOUT_NOT_FOUND: "Không tìm thấy báo giá này. Vui lòng xem lại đơn hàng.",
  SUBMISSION_NOT_FOUND: "Báo giá này chưa được đặt hàng.",
  CHECKOUT_SUPERSEDED: "Báo giá này đã được thay bằng báo giá mới hơn.",
  CHECKOUT_EXPIRED: "Báo giá đã hết hạn. Vui lòng xem lại đơn hàng.",
  ACTIVE_CHECKOUT_CHANGED: "Đơn hàng vừa được cập nhật ở một cửa sổ khác. Vui lòng tải lại.",
  SUBMISSION_IN_PROGRESS:
    "Bạn đang có một đơn hàng được xử lý. Đừng đặt lại — hãy chờ kết quả trong giây lát.",
  CHECKOUT_ALREADY_SUBMITTED: "Báo giá này đã được đặt hàng. Hãy xem trạng thái đơn hàng.",
  SUBMISSION_NOT_ACCEPTED: "Đơn hàng chưa được tạo. Vui lòng xem lại đơn hàng và đặt lại.",
  SUBMISSION_REJECTED: "Chưa thể tạo đơn hàng này. Vui lòng xem lại đơn hàng và thử lại.",
  CHECKOUT_UNAVAILABLE: "Thanh toán đang gián đoạn. Vui lòng thử lại sau giây lát.",
  VOUCHER_OPTIONS_UNAVAILABLE:
    "Chưa tải được danh sách voucher. Bạn vẫn có thể đặt hàng không dùng voucher.",
};

export const ORDER_MESSAGES: Record<string, string> = {
  INVALID_ORDER_TRANSITION: "Trạng thái đơn hàng hiện tại không cho phép thao tác này.",
  IDEMPOTENCY_KEY_REUSED: "Nội dung thao tác đã thay đổi. Vui lòng đóng và thực hiện lại.",
  REFUND_CUSTOMER_REQUEST_ALREADY_USED: "Đơn hàng này đã gửi yêu cầu trả hàng trước đó.",
  REFUND_REQUIRES_ALL_ITEMS_DELIVERED:
    "Chỉ có thể yêu cầu trả hàng sau khi đã nhận đủ tất cả sản phẩm.",
  INVALID_REFUND_SCOPE: "Sản phẩm chọn để trả chưa hợp lệ. Vui lòng chọn lại.",
  REFUND_CASE_ALREADY_EXISTS: "Đơn hàng này đã có một hồ sơ trả hàng hoặc hoàn tiền.",
  PAYMENT_RETRY_NOT_ELIGIBLE: "Đơn hàng hiện không đủ điều kiện để thanh toán lại.",
  OPERATION_IN_PROGRESS: "Một thao tác cho đơn hàng này đang được xử lý. Vui lòng chờ.",
  INVENTORY_RESERVATION_REJECTED: "Kho không thể giữ hàng cho đơn này.",
  PAYMENT_INITIATION_REJECTED: "Không thể khởi tạo thanh toán. Vui lòng thử lại sau.",
  CHECKOUT_PAYMENT_WINDOW_EXHAUSTED: "Thời gian giữ hàng không còn đủ để thanh toán.",
  ORDER_CANCELLED_DURING_PREPARATION: "Đơn hàng đã được huỷ trước khi thanh toán sẵn sàng.",
  ORDER_UNAVAILABLE: "Thông tin đơn hàng đang gián đoạn. Vui lòng thử lại sau.",
  ADMIN_REFUND_REQUIRES_ALL_UNDISPATCHED:
    "Không thể hoàn tiền tự động vì đã có sản phẩm rời kho.",
  PROMOTION_RECALCULATION_NEEDED: "Voucher cần được tính lại. Vui lòng xem lại đơn hàng.",
  PAYMENT_TIMEOUT: "Đơn hàng đã quá thời hạn thanh toán.",
};

export const INVENTORY_MESSAGES: Record<string, string> = {
  INVENTORY_BUSY: "Kho đang xử lý nhiều yêu cầu. Hãy thử lại đúng lệnh này sau giây lát.",
  INVENTORY_UNAVAILABLE: "Hệ thống kho tạm thời gián đoạn. Hãy thử lại đúng lệnh này sau.",
  DISPATCH_NOT_ALLOWED:
    "Dòng hàng không còn đủ điều kiện xuất kho hoặc dữ liệu phiếu không khớp phân bổ hiện tại.",
  OPERATION_IDENTITY_CONFLICT:
    "Mã phiếu hoặc mã thao tác đã được dùng. Hãy tra kết quả lệnh và đối chiếu chứng từ.",
  WAREHOUSE_NOT_FOUND: "Không tìm thấy kho. Hãy kiểm tra lại mã kho.",
  RETURN_RECEIPT_REJECTED: "Không thể nhận hàng: dòng đơn, SKU hoặc số lượng không khớp yêu cầu trả. Hãy tải lại và đối chiếu kiện hàng.",
  INSPECTION_REJECTED: "Không thể kiểm định: kiểm tra kho nhận, phiếu nhận và số lượng chưa kiểm định rồi tải lại hàng đợi.",
  DISPOSITION_REJECTED: "Không thể xử lý cách ly: kiểm tra kết quả kiểm định, số lượng còn lại và chứng từ hàng rời hoặc quay về kho.",
  REFUND_SCOPE_STATE_CONFLICT: "Yêu cầu trả hàng đã chuyển trạng thái và không nhận thêm hàng. Hãy tải lại yêu cầu.",
  INSUFFICIENT_SELLABLE_STOCK: "Không đủ tồn bán được cho thao tác này. Hãy tải lại vị trí tồn.",
  MOVEMENT_NOT_REVERSIBLE: "Biến động này không thể đảo bằng điều chỉnh tồn. Hãy dùng đúng quy trình hàng trả hoặc cách ly.",
  WAREHOUSE_CONFLICT: "Kho vừa được cập nhật. Hãy tải lại trước khi sửa tiếp.",
};
