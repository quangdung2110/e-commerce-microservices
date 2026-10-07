import { AxiosError, AxiosHeaders, type InternalAxiosRequestConfig } from "axios";

/** Dựng một AxiosError có response giả, như khi server trả lỗi HTTP. */
export function httpError(
  status: number,
  data: unknown = undefined,
  headers: Record<string, string> = {}
): AxiosError {
  const config = { headers: new AxiosHeaders() } as InternalAxiosRequestConfig;
  return new AxiosError("Request failed", "ERR_BAD_RESPONSE", config, null, {
    status,
    statusText: "",
    data,
    headers,
    config,
  });
}

/** AxiosError không có response — request chưa tới được server. */
export function networkError(): AxiosError {
  const config = { headers: new AxiosHeaders() } as InternalAxiosRequestConfig;
  return new AxiosError("Network Error", "ERR_NETWORK", config, null, undefined);
}

export function problem(code: string, status: number, extra: Record<string, unknown> = {}) {
  return {
    type: "about:blank",
    title: code,
    status,
    detail: "English detail for developers",
    instance: "/api/v1/test",
    code,
    ...extra,
  };
}
