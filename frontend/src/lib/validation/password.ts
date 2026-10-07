import * as z from "zod";

/**
 * Chính sách mật khẩu, phản chiếu
 * `go/services/auth-service/internal/domain/vo/password.go` và mặc định
 * `security.password_policy` (12–128 ký tự).
 *
 * Kiểm ở client chỉ để phản hồi sớm cho người dùng — server vẫn là nơi quyết
 * định, và nó còn hai luật nữa mà client không đoán được: mật khẩu không được
 * chứa email, và không được là một ký tự lặp lại.
 */
export const PASSWORD_MIN_LENGTH = 12;
export const PASSWORD_MAX_LENGTH = 128;

export const PASSWORD_HINT = `Ít nhất ${PASSWORD_MIN_LENGTH} ký tự, không trùng với email của bạn.`;

export const passwordSchema = z
  .string()
  .min(PASSWORD_MIN_LENGTH, {
    message: `Mật khẩu cần ít nhất ${PASSWORD_MIN_LENGTH} ký tự.`,
  })
  .max(PASSWORD_MAX_LENGTH, {
    message: `Mật khẩu tối đa ${PASSWORD_MAX_LENGTH} ký tự.`,
  })
  .refine((value) => new Set(value).size > 1, {
    message: "Mật khẩu không được lặp lại một ký tự duy nhất.",
  });

/** Thông báo dùng chung khi hai ô mật khẩu không khớp. */
export const PASSWORD_MISMATCH = "Hai mật khẩu chưa khớp nhau.";
