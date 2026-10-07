import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/user";

/**
 * User Service — HTTP client theo `contracts/http/user/v1/openapi.yaml`.
 *
 * Ba điểm dễ sai, đã được xử lý ở đây:
 *
 *  1. Response **không** có lớp bọc `{ data: ... }` — server trả thẳng object.
 *  2. `PATCH /profile` bắt buộc `Content-Type: application/merge-patch+json`;
 *     gửi `application/json` sẽ bị từ chối 400.
 *  3. Decoder từ chối field lạ: `display_name` (read-only), `language` và
 *     `currency` (read-only) không được phép xuất hiện trong body.
 */

const BASE = "/api/v1/users/me";

type Schemas = components["schemas"];

export type Profile = Schemas["Profile"];
export type Gender = Profile["gender"];
/**
 * JSON Merge Patch (RFC 7396). Vắng mặt = giữ nguyên, `null` = xoá giá trị.
 * Riêng `gender` không nhận `null`: dùng `"UNKNOWN"` để nói "không tiết lộ".
 */
export type ProfilePatch = Schemas["ProfilePatchRequest"];
export type Preferences = Schemas["Preferences"];
/** PUT thay toàn bộ: cả ba cờ đều bắt buộc, thiếu một cái là 400. */
export type PreferencesUpdate = Schemas["NotificationPreferencesUpdateRequest"];
export type Address = Schemas["Address"];
/** POST và PUT dùng chung payload này; PUT thay toàn bộ nên mọi field bắt buộc. */
export type AddressWrite = Schemas["AddressWriteRequest"];

const MERGE_PATCH = { headers: { "Content-Type": "application/merge-patch+json" } };

export const userApi = {
  getProfile: async (): Promise<Profile> => {
    const response = await api.get<Profile>(`${BASE}/profile`);
    return response.data;
  },

  /** Chỉ gửi những field thực sự thay đổi — đó là ý nghĩa của merge patch. */
  patchProfile: async (patch: ProfilePatch): Promise<Profile> => {
    const response = await api.patch<Profile>(`${BASE}/profile`, patch, MERGE_PATCH);
    return response.data;
  },

  getPreferences: async (): Promise<Preferences> => {
    const response = await api.get<Preferences>(`${BASE}/preferences`);
    return response.data;
  },

  replacePreferences: async (update: PreferencesUpdate): Promise<Preferences> => {
    const response = await api.put<Preferences>(`${BASE}/preferences`, update);
    return response.data;
  },

  listAddresses: async (): Promise<Address[]> => {
    const response = await api.get<Schemas["AddressList"]>(`${BASE}/addresses`);
    return response.data.addresses;
  },

  createAddress: async (input: AddressWrite): Promise<Address> => {
    const response = await api.post<Address>(`${BASE}/addresses`, input);
    return response.data;
  },

  replaceAddress: async (id: string, input: AddressWrite): Promise<Address> => {
    const response = await api.put<Address>(`${BASE}/addresses/${id}`, input);
    return response.data;
  },

  deleteAddress: async (id: string): Promise<void> => {
    await api.delete(`${BASE}/addresses/${id}`);
  },

  /** Idempotent, không có body, trả về địa chỉ đã thành mặc định. */
  setDefaultAddress: async (id: string): Promise<Address> => {
    const response = await api.put<Address>(`${BASE}/addresses/${id}/default`);
    return response.data;
  },
};
