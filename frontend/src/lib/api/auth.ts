import { api, refreshSession, type AccessTokenResponse } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/auth";
import { useAuthStore, type SignOutReason } from "@/store/useAuthStore";

/**
 * Auth Service — HTTP client theo `contracts/http/auth/v1/openapi.yaml`.
 *
 * API theo phong cách resource: đăng nhập là *tạo một session*, xác minh email là
 * *tạo một email-verification*, chứ không phải `/login` hay `/verify-email`.
 *
 * Bốn thao tác thu hồi **toàn bộ** phiên, kể cả phiên hiện tại, và xoá cookie
 * refresh: đổi mật khẩu, xác nhận đổi email, thu hồi tất cả phiên, xoá tài khoản.
 * Các hàm đó tự xoá trạng thái đăng nhập local; caller chỉ cần điều hướng về
 * trang đăng nhập.
 */

const BASE = "/api/v1/auth";

type Schemas = components["schemas"];

export type RegistrationRequest = Schemas["RegistrationRequest"];
export type RegistrationAccepted = Schemas["RegistrationAccepted"];
export type LoginRequest = Schemas["LoginRequest"];
export type EmailVerificationRequest = Schemas["EmailVerificationRequest"];
export type CompletePasswordResetRequest = Schemas["CompletePasswordResetRequest"];
export type ChangePasswordRequest = Schemas["ChangePasswordRequest"];
export type Session = Schemas["Session"];
export type SessionList = Schemas["SessionList"];
export type AccountDeletionAccepted = Schemas["AccountDeletionAccepted"];

/**
 * Nhãn thiết bị hiển thị trong danh sách phiên đăng nhập. Auth Service tự lưu
 * User-Agent đầy đủ; đây chỉ là cái tên dễ đọc cho người dùng.
 */
function deviceName(): string | undefined {
  if (typeof navigator === "undefined") return undefined;
  const ua = navigator.userAgent;
  const browser =
    /Edg\//.test(ua) ? "Edge"
    : /OPR\//.test(ua) ? "Opera"
    : /Chrome\//.test(ua) ? "Chrome"
    : /Firefox\//.test(ua) ? "Firefox"
    : /Safari\//.test(ua) ? "Safari"
    : "Trình duyệt";
  const platform =
    /Windows/.test(ua) ? "Windows"
    : /Android/.test(ua) ? "Android"
    : /iPhone|iPad/.test(ua) ? "iOS"
    : /Mac OS X/.test(ua) ? "macOS"
    : /Linux/.test(ua) ? "Linux"
    : "";
  return platform ? `${browser} trên ${platform}` : browser;
}

/**
 * Server đã thu hồi phiên: bỏ access token local. Gợi ý email được giữ để ô
 * đăng nhập điền sẵn, trừ khi tài khoản không còn nữa.
 */
function endLocalSession(reason: SignOutReason, { forgetEmail = false } = {}) {
  const store = useAuthStore.getState();
  if (forgetEmail) store.clear(reason);
  else store.markAnonymous(reason);
}

export const authApi = {
  /** Tạo tài khoản. Tài khoản ở trạng thái chờ xác minh cho tới khi nhập OTP. */
  register: async (data: RegistrationRequest) => {
    const response = await api.post<RegistrationAccepted>(`${BASE}/registrations`, data);
    return response.data;
  },

  /** Gửi lại mã OTP xác minh email. Trả về như nhau dù email có tồn tại hay không. */
  requestEmailVerification: async (email: string) => {
    await api.post(`${BASE}/email-verification-requests`, { email });
  },

  /** Xác minh email bằng OTP. */
  verifyEmail: async (data: EmailVerificationRequest) => {
    await api.post(`${BASE}/email-verifications`, data);
  },

  /**
   * Đăng nhập. Access token nằm trong body; refresh token đi bằng cookie
   * `auth_refresh` HttpOnly mà JavaScript không đọc được.
   */
  login: async (data: LoginRequest) => {
    const response = await api.post<AccessTokenResponse>(`${BASE}/sessions`, {
      ...data,
      device_name: data.device_name ?? deviceName(),
    });
    useAuthStore.getState().setSession(response.data.access_token, data.email);
    return response.data;
  },

  /** Khôi phục phiên từ cookie refresh. Dùng khi tải lại trang. */
  restoreSession: refreshSession,

  /**
   * Đăng xuất. Thu hồi phiên hiện tại ở phía server rồi xoá trạng thái local.
   *
   * Việc thu hồi là "cố gắng hết sức": nếu nó thất bại (token đã hết hạn, mạng
   * hỏng) thì người dùng vẫn phải được đăng xuất khỏi trình duyệt này.
   */
  logout: async () => {
    const sessionId = useAuthStore.getState().claims?.sid;
    try {
      if (sessionId) await api.delete(`${BASE}/sessions/${sessionId}`);
    } catch {
      /* bỏ qua: xoá trạng thái local vẫn quan trọng hơn */
    } finally {
      endLocalSession("logged_out", { forgetEmail: true });
    }
  },

  /** Bắt đầu luồng quên mật khẩu. Luôn trả về thành công để không lộ email nào tồn tại. */
  requestPasswordReset: async (email: string) => {
    await api.post(`${BASE}/password-reset-requests`, { email });
  },

  /** Hoàn tất đặt lại mật khẩu bằng token trong email. */
  completePasswordReset: async (data: CompletePasswordResetRequest) => {
    await api.post(`${BASE}/password-resets`, data);
  },

  /**
   * Đổi mật khẩu khi đã đăng nhập. Server thu hồi **mọi** phiên, kể cả phiên
   * này, nên người dùng phải đăng nhập lại bằng mật khẩu mới.
   */
  changePassword: async (data: ChangePasswordRequest) => {
    await api.put(`${BASE}/password`, data);
    endLocalSession("password_changed");
  },

  /** Danh sách phiên đăng nhập đang hoạt động của chính mình. */
  listSessions: async (cursor?: string, signal?: AbortSignal) => {
    const response = await api.get<SessionList>(`${BASE}/sessions`, {
      params: cursor ? { cursor } : undefined,
      signal,
    });
    return response.data;
  },

  /**
   * Thu hồi một phiên. Nếu đó là phiên hiện tại thì server xoá cookie refresh,
   * nên trạng thái local cũng phải bỏ theo.
   */
  revokeSession: async (sessionId: string) => {
    await api.delete(`${BASE}/sessions/${sessionId}`);
    if (sessionId === useAuthStore.getState().claims?.sid) endLocalSession("logged_out");
  },

  /** Thu hồi mọi phiên, kể cả phiên hiện tại. */
  revokeAllSessions: async () => {
    await api.delete(`${BASE}/sessions`);
    endLocalSession("sessions_revoked");
  },

  /** Yêu cầu đổi email; OTP được gửi tới địa chỉ mới. Email chưa đổi ở bước này. */
  requestEmailChange: async (currentPassword: string, newEmail: string) => {
    await api.post(`${BASE}/email-change-requests`, {
      current_password: currentPassword,
      new_email: newEmail,
    });
  },

  /**
   * Hoàn tất đổi email. Server thu hồi mọi phiên; email mới được giữ làm gợi ý
   * cho ô đăng nhập.
   */
  confirmEmailChange: async (newEmail: string, otp: string) => {
    await api.post(`${BASE}/email-changes/confirmations`, { new_email: newEmail, otp });
    useAuthStore.getState().setEmailHint(newEmail);
    endLocalSession("email_changed");
  },

  /** Yêu cầu xoá tài khoản của chính mình (GDPR). Không khôi phục được. */
  requestOwnDeletion: async (currentPassword: string) => {
    const response = await api.post<AccountDeletionAccepted>(
      `${BASE}/accounts/me/deletion-requests`,
      { current_password: currentPassword }
    );
    endLocalSession("account_deleted", { forgetEmail: true });
    return response.data;
  },
};
