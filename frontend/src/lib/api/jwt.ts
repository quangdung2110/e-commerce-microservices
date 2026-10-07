/**
 * Claim của access token do Auth Service phát.
 *
 * Nguồn: `go/services/auth-service/internal/domain/service/token_service.go`.
 * Chỉ có bấy nhiêu — token **không** chứa email, nên frontend không thể lấy email
 * người dùng từ đây (xem `useAuthStore.emailHint`).
 */
export interface AccessTokenClaims {
  iss: string;
  aud: string;
  sub: string;
  jti: string;
  /** Family id của session, ổn định qua các lần rotate refresh token. */
  sid: string;
  roles: string[];
  iat: number;
  nbf: number;
  exp: number;
}

/**
 * Đọc payload của JWT **không xác minh chữ ký**.
 *
 * Chữ ký chỉ được xác minh ở API Gateway. Ở client, payload chỉ dùng cho việc
 * hiển thị và điều hướng; không bao giờ dùng nó để ra quyết định phân quyền.
 */
export function decodeAccessToken(token: string): AccessTokenClaims | null {
  const payload = token.split(".")[1];
  if (!payload) return null;

  try {
    const base64 = payload.replace(/-/g, "+").replace(/_/g, "/");
    const padded = base64.padEnd(base64.length + ((4 - (base64.length % 4)) % 4), "=");
    // atob cho ra chuỗi byte; bước escape/decodeURIComponent khôi phục UTF-8.
    const json = decodeURIComponent(
      atob(padded)
        .split("")
        .map((c) => `%${c.charCodeAt(0).toString(16).padStart(2, "0")}`)
        .join("")
    );
    const claims = JSON.parse(json) as AccessTokenClaims;
    return typeof claims?.sub === "string" ? claims : null;
  } catch {
    return null;
  }
}

/** Token đã hết hạn chưa, có trừ hao `skewSeconds` để chủ động refresh sớm. */
export function isExpired(claims: AccessTokenClaims | null, skewSeconds = 30): boolean {
  if (!claims) return true;
  return claims.exp * 1000 - skewSeconds * 1000 <= Date.now();
}
