import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { userService, UserProfile, UserAddress, UserPreferences } from "@/services/user/api";

export const USER_QUERY_KEYS = {
  profile: ["user", "profile"],
  addresses: ["user", "addresses"],
  preferences: ["user", "preferences"],
};

// Profile Hooks
export function useProfile() {
  return useQuery({
    queryKey: USER_QUERY_KEYS.profile,
    queryFn: userService.getProfile,
  });
}

export function useUpdateProfile() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: Partial<UserProfile>) => userService.updateProfile(data),
    onSuccess: (updatedProfile) => {
      queryClient.setQueryData(USER_QUERY_KEYS.profile, updatedProfile);
    },
  });
}

// Addresses Hooks
export function useAddresses() {
  return useQuery({
    queryKey: USER_QUERY_KEYS.addresses,
    queryFn: userService.getAddresses,
  });
}

export function useCreateAddress() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: Omit<UserAddress, "id" | "user_id" | "is_default">) =>
      userService.createAddress(data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: USER_QUERY_KEYS.addresses });
    },
  });
}

export function useUpdateAddress() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      id,
      data,
    }: {
      id: string;
      data: Partial<Omit<UserAddress, "id" | "user_id">>;
    }) => userService.updateAddress(id, data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: USER_QUERY_KEYS.addresses });
    },
  });
}

export function useDeleteAddress() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => userService.deleteAddress(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: USER_QUERY_KEYS.addresses });
    },
  });
}

export function useSetDefaultAddress() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => userService.setDefaultAddress(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: USER_QUERY_KEYS.addresses });
    },
  });
}

// Preferences Hooks
export function usePreferences() {
  return useQuery({
    queryKey: USER_QUERY_KEYS.preferences,
    queryFn: userService.getPreferences,
  });
}

export function useUpdatePreferences() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: Partial<UserPreferences>) =>
      userService.updatePreferences(data),
    onSuccess: (updatedPrefs) => {
      queryClient.setQueryData(USER_QUERY_KEYS.preferences, updatedPrefs);
    },
  });
}
