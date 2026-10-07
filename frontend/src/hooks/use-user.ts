"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import {
  userApi,
  type Address,
  type AddressWrite,
  type Preferences,
  type PreferencesUpdate,
  type Profile,
  type ProfilePatch,
} from "@/lib/api/user";
import { useAuthStore } from "@/store/useAuthStore";

export const USER_QUERY_KEYS = {
  profile: ["user", "profile"] as const,
  addresses: ["user", "addresses"] as const,
  preferences: ["user", "preferences"] as const,
};

/**
 * Query của User Service chỉ chạy khi đã có phiên. Trong lúc `status` còn là
 * "loading" (đang khôi phục phiên từ cookie refresh) mà bắn request thì sẽ ăn
 * một loạt 401 vô nghĩa.
 */
function useAuthenticated() {
  return useAuthStore((state) => state.status) === "authenticated";
}

// ── Profile ────────────────────────────────────────────────────────────────

export function useProfile() {
  const enabled = useAuthenticated();
  return useQuery({
    queryKey: USER_QUERY_KEYS.profile,
    queryFn: userApi.getProfile,
    enabled,
  });
}

export function useUpdateProfile() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (patch: ProfilePatch) => userApi.patchProfile(patch),
    onSuccess: (profile: Profile) => {
      // Server trả về bản ghi đầy đủ sau patch, kể cả display_name vừa dẫn xuất lại.
      queryClient.setQueryData(USER_QUERY_KEYS.profile, profile);
    },
  });
}

// ── Preferences ────────────────────────────────────────────────────────────

export function usePreferences() {
  const enabled = useAuthenticated();
  return useQuery({
    queryKey: USER_QUERY_KEYS.preferences,
    queryFn: userApi.getPreferences,
    enabled,
  });
}

export function useUpdatePreferences() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (update: PreferencesUpdate) => userApi.replacePreferences(update),
    onSuccess: (preferences: Preferences) => {
      queryClient.setQueryData(USER_QUERY_KEYS.preferences, preferences);
    },
  });
}

// ── Addresses ──────────────────────────────────────────────────────────────

export function useAddresses() {
  const enabled = useAuthenticated();
  return useQuery({
    queryKey: USER_QUERY_KEYS.addresses,
    queryFn: userApi.listAddresses,
    enabled,
  });
}

export function useCreateAddress() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: AddressWrite) => userApi.createAddress(input),
    onSuccess: () => {
      // Địa chỉ đầu tiên tự thành mặc định, nên phải nạp lại cả danh sách.
      queryClient.invalidateQueries({ queryKey: USER_QUERY_KEYS.addresses });
    },
  });
}

export function useReplaceAddress() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, input }: { id: string; input: AddressWrite }) =>
      userApi.replaceAddress(id, input),
    onSuccess: (address: Address) => {
      queryClient.setQueryData<Address[]>(USER_QUERY_KEYS.addresses, (current) =>
        current?.map((item) => (item.id === address.id ? address : item))
      );
    },
  });
}

export function useDeleteAddress() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => userApi.deleteAddress(id),
    onSuccess: (_, id) => {
      queryClient.setQueryData<Address[]>(USER_QUERY_KEYS.addresses, (current) =>
        current?.filter((item) => item.id !== id)
      );
    },
  });
}

export function useSetDefaultAddress() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => userApi.setDefaultAddress(id),
    onSuccess: () => {
      // Đặt mặc định làm địa chỉ cũ mất cờ default, nên nạp lại toàn bộ.
      queryClient.invalidateQueries({ queryKey: USER_QUERY_KEYS.addresses });
    },
  });
}
