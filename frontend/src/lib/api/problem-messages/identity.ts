/** Auth Service và User Service. */
export const AUTH_MESSAGES: Record<string, string> = {
  INVALID_CREDENTIALS: "Email hoặc mật khẩu không đúng.",
  ACCOUNT_NOT_VERIFIED: "Tài khoản chưa xác minh email. Vui lòng nhập mã xác minh để tiếp tục.",
  ACCOUNT_LOCKED:
    "Tài khoản đang tạm khoá do đăng nhập sai quá nhiều lần. Vui lòng thử lại sau.",
  ACCOUNT_NOT_ACTIVE: "Tài khoản đang ở trạng thái không thể thực hiện thao tác này.",
  ACCOUNT_DELETED: "Tài khoản này đã bị xoá.",
  ACCOUNT_NOT_FOUND: "Không tìm thấy tài khoản.",
  EMAIL_ALREADY_REGISTERED: "Email này đã được đăng ký. Bạn có muốn đăng nhập không?",
  REFRESH_TOKEN_INVALID: "Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.",
  SESSION_NOT_FOUND: "Phiên đăng nhập không tồn tại hoặc đã bị thu hồi.",
  OTP_INVALID: "Mã xác minh không đúng hoặc đã hết hạn. Vui lòng yêu cầu mã mới.",
  RESET_TOKEN_INVALID:
    "Liên kết đặt lại mật khẩu không còn hiệu lực. Vui lòng yêu cầu liên kết mới.",
  // Chính sách thật của Auth Service: 12–128 ký tự, không chứa email, không lặp
  // lại một ký tự duy nhất (internal/domain/vo/password.go).
  PASSWORD_POLICY_VIOLATION:
    "Mật khẩu chưa đạt yêu cầu: cần 12–128 ký tự, không trùng với email và không lặp lại một ký tự duy nhất.",
  SIGNING_KEY_MISSING: "Hệ thống xác thực đang khởi động. Vui lòng thử lại sau ít phút.",
};

export const USER_MESSAGES: Record<string, string> = {
  PROFILE_NOT_FOUND: "Không tìm thấy hồ sơ của bạn.",
  ADDRESS_NOT_FOUND: "Địa chỉ này không còn tồn tại.",
  DEFAULT_ADDRESS_CANNOT_BE_DELETED:
    "Hãy đặt một địa chỉ khác làm mặc định trước khi xoá địa chỉ này.",
  DEFAULT_ADDRESS_CONFLICT:
    "Địa chỉ mặc định đang xung đột. Vui lòng tải lại trang rồi thử lại.",
  INVALID_PROFILE_FIELD: "Một vài thông tin trong hồ sơ chưa hợp lệ.",
  INVALID_ADDRESS: "Địa chỉ, số điện thoại hoặc mã địa giới chưa hợp lệ.",
  INVALID_PREFERENCE: "Tuỳ chọn thông báo chưa hợp lệ.",
  DELETION_ALREADY_PROCESSED: "Yêu cầu xoá tài khoản đã được xử lý trước đó.",
};
