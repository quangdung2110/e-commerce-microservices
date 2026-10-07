import { create } from "zustand";

import { decodeAccessToken, type AccessTokenClaims } from "@/lib/api/jwt";

/**
 * Khoá localStorage giữ email người dùng đã nhập lúc đăng nhập.
 *
 * Access token của Auth Service **không có claim email** và profile của User
 * Service cũng không trả email, nên đây là cách duy nhất để hiển thị lại email
 * sau khi tải lại trang. Giá trị này chỉ để hiển thị, không bao giờ dùng để xác
 * thực, và bị xoá khi đăng xuất.
 */
const EMAIL_HINT_KEY = "technova.auth.email";

type AuthStatus = "loading" | "authenticated" | "anonymous";

/**
 * Vì sao phiên vừa kết thúc, để trang đăng nhập giải thích cho người dùng thay
 * vì chỉ hiện form trống. `null` khi không có gì cần nói (lần đầu vào trang,
 * cookie refresh hết hạn tự nhiên).
 */
export type SignOutReason =
  | "logged_out"
  | "password_changed"
  | "email_changed"
  | "sessions_revoked"
  | "account_deleted"
  | "revoked";

interface AuthState {
  /**
   * Access token chỉ nằm trong bộ nhớ, không vào localStorage: refresh token đã
   * ở cookie HttpOnly nên phiên vẫn khôi phục được sau khi tải lại trang, mà
   * access token thì không bị lộ cho script bên thứ ba.
   */
  accessToken: string | null;
  claims: AccessTokenClaims | null;
  emailHint: string | null;
  status: AuthStatus;
  signOutReason: SignOutReason | null;

  setSession: (accessToken: string, email?: string | null) => void;
  setEmailHint: (email: string) => void;
  /** Hết phiên nhưng giữ gợi ý email cho ô đăng nhập. */
  markAnonymous: (reason?: SignOutReason) => void;
  /** Hết phiên và quên luôn email (đăng xuất chủ động, xoá tài khoản). */
  clear: (reason?: SignOutReason) => void;
  acknowledgeSignOutReason: () => void;
}

function readEmailHint(): string | null {
  if (typeof window === "undefined") return null;
  try {
    return window.localStorage.getItem(EMAIL_HINT_KEY);
  } catch {
    // Trình duyệt chặn site data (chế độ riêng tư, thiết lập bảo mật).
    return null;
  }
}

function writeEmailHint(email: string | null) {
  if (typeof window === "undefined") return;
  try {
    if (email) window.localStorage.setItem(EMAIL_HINT_KEY, email);
    else window.localStorage.removeItem(EMAIL_HINT_KEY);
  } catch {
    /* bỏ qua: đây chỉ là tiện ích hiển thị */
  }
}

export const useAuthStore = create<AuthState>((set, get) => ({
  accessToken: null,
  claims: null,
  emailHint: null,
  // "loading" cho tới khi lần refresh đầu tiên trả lời — nếu bắt đầu bằng
  // "anonymous" thì màn hình protected sẽ chớp một nhịp redirect sai.
  status: "loading",
  signOutReason: null,

  setSession: (accessToken, email) => {
    const claims = decodeAccessToken(accessToken);
    const nextEmail = email ?? get().emailHint ?? readEmailHint();
    if (email) writeEmailHint(email);
    set({
      accessToken,
      claims,
      emailHint: nextEmail,
      status: "authenticated",
      signOutReason: null,
    });
  },

  setEmailHint: (email) => {
    writeEmailHint(email);
    set({ emailHint: email });
  },

  markAnonymous: (reason) =>
    set({
      accessToken: null,
      claims: null,
      status: "anonymous",
      signOutReason: reason ?? null,
    }),

  clear: (reason) => {
    writeEmailHint(null);
    set({
      accessToken: null,
      claims: null,
      emailHint: null,
      status: "anonymous",
      signOutReason: reason ?? null,
    });
  },

  acknowledgeSignOutReason: () => set({ signOutReason: null }),
}));

/** Đọc token ngoài React (dùng trong interceptor của axios). */
export function currentAccessToken(): string | null {
  return useAuthStore.getState().accessToken;
}

/** Khôi phục email hint lúc khởi động, trước khi biết phiên còn hiệu lực hay không. */
export function hydrateEmailHint() {
  const hint = readEmailHint();
  if (hint) useAuthStore.setState({ emailHint: hint });
}
