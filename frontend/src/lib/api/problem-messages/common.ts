/** Mã lỗi dùng chung cho mọi service và cho API Gateway. */
export const COMMON_MESSAGES: Record<string, string> = {
  VALIDATION_FAILED: "Thông tin gửi lên chưa hợp lệ. Vui lòng kiểm tra lại các ô bên dưới.",
  INVALID_REQUEST: "Thông tin gửi lên chưa hợp lệ. Vui lòng kiểm tra lại.",
  UNAUTHORIZED: "Phiên đăng nhập đã kết thúc. Vui lòng đăng nhập lại.",
  UNAUTHENTICATED: "Phiên đăng nhập đã kết thúc. Vui lòng đăng nhập lại.",
  TOKEN_INVALID: "Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.",
  TOKEN_REVOKED: "Phiên đăng nhập này đã bị thu hồi. Vui lòng đăng nhập lại.",
  FORBIDDEN: "Tài khoản của bạn không có quyền thực hiện thao tác này.",
  NOT_FOUND: "Không tìm thấy nội dung bạn yêu cầu.",
  METHOD_NOT_ALLOWED: "Thao tác không được hỗ trợ.",
  RATE_LIMITED: "Bạn đã thao tác quá nhanh. Vui lòng chờ một lát rồi thử lại.",
  IDEMPOTENCY_KEY_REUSED: "Thao tác này đã được gửi với nội dung khác. Vui lòng tải lại trang rồi thử lại.",
  IDEMPOTENCY_CONFLICT: "Thao tác trước đó vẫn đang được xử lý. Vui lòng chờ một lát.",
  IDEMPOTENCY_PAYLOAD_MISMATCH: "Thao tác này đã được gửi với nội dung khác. Vui lòng tải lại trang rồi thử lại.",
  INVALID_STATE_TRANSITION: "Trạng thái hiện tại không cho phép thao tác này. Vui lòng tải lại trang.",
  DEPENDENCY_UNAVAILABLE: "Hệ thống đang bận. Vui lòng thử lại sau ít phút.",
  INTERNAL_ERROR: "Đã có lỗi xảy ra ở phía hệ thống. Vui lòng thử lại sau.",
};

/**
 * Câu dự phòng theo HTTP status, dùng khi `code` chưa có trong bảng. Tốt hơn câu
 * chung chung vì status vẫn nói được người dùng nên làm gì tiếp.
 */
export const STATUS_MESSAGES: Record<number, string> = {
  400: "Thông tin gửi lên chưa hợp lệ. Vui lòng kiểm tra lại.",
  401: "Phiên đăng nhập đã kết thúc. Vui lòng đăng nhập lại.",
  403: "Tài khoản của bạn không có quyền thực hiện thao tác này.",
  404: "Không tìm thấy nội dung bạn yêu cầu.",
  409: "Dữ liệu vừa thay đổi nên thao tác chưa thực hiện được. Vui lòng tải lại trang rồi thử lại.",
  410: "Nội dung này đã hết hiệu lực. Vui lòng tải lại để lấy thông tin mới.",
  412: "Dữ liệu vừa được người khác thay đổi. Hãy tải lại để xem bản mới trước khi sửa tiếp.",
  422: "Yêu cầu chưa đáp ứng điều kiện nghiệp vụ. Vui lòng kiểm tra lại.",
  429: "Bạn đã thao tác quá nhanh. Vui lòng chờ một lát rồi thử lại.",
  503: "Hệ thống đang bận. Vui lòng thử lại sau ít phút.",
  504: "Hệ thống phản hồi quá chậm. Vui lòng thử lại.",
};
