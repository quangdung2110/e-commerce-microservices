import axios from 'axios';
import { getDeviceId } from '@/lib/api/auth';

export interface UserProfile {
  id: string;
  first_name: string;
  last_name: string;
  phone_number: string | null;
  date_of_birth: string | null;
  gender: "MALE" | "FEMALE" | "OTHER" | "UNKNOWN";
}

export interface UserAddress {
  id: string;
  user_id: string;
  is_default: boolean;
  recipient_name: string;
  phone_number: string;
  address_line: string;
  ward_id: string;
  district_id: string;
  city_id: string;
}

export interface UserPreferences {
  user_id: string;
  language: string;
  currency: string;
  email_enabled: boolean;
  in_app_enabled: boolean;
  marketing_enabled: boolean;
}

const API_BASE_URL = process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8088/api/v1';

// Setup Axios instance for User Service
export const userApiClient = axios.create({
  baseURL: `${API_BASE_URL}/users/me`,
  headers: {
    'Content-Type': 'application/json',
  },
  withCredentials: true, // IMPORTANT: To send/receive HttpOnly cookies (refresh_token)
});

// Add interceptor to inject X-Device-ID (if needed)
userApiClient.interceptors.request.use((config) => {
  config.headers['X-Device-ID'] = getDeviceId();
  // TODO: Add Authorization header here if tokens are stored in localStorage/zustand
  return config;
});

export const userService = {
  getProfile: async (): Promise<UserProfile> => {
    const res = await userApiClient.get<{ data: UserProfile }>('/profile');
    return res.data.data;
  },

  updateProfile: async (data: Partial<UserProfile>): Promise<UserProfile> => {
    const res = await userApiClient.put<{ data: UserProfile }>('/profile', data);
    return res.data.data;
  },

  getAddresses: async (): Promise<UserAddress[]> => {
    const res = await userApiClient.get<{ data: UserAddress[] }>('/addresses');
    return res.data.data;
  },

  createAddress: async (
    data: Omit<UserAddress, "id" | "user_id" | "is_default">
  ): Promise<UserAddress> => {
    const res = await userApiClient.post<{ data: UserAddress }>('/addresses', data);
    return res.data.data;
  },

  updateAddress: async (
    id: string,
    data: Partial<Omit<UserAddress, "id" | "user_id">>
  ): Promise<UserAddress> => {
    const res = await userApiClient.put<{ data: UserAddress }>(`/addresses/${id}`, data);
    return res.data.data;
  },

  deleteAddress: async (id: string): Promise<void> => {
    await userApiClient.delete(`/addresses/${id}`);
  },

  setDefaultAddress: async (id: string): Promise<void> => {
    await userApiClient.post(`/addresses/${id}/default`);
  },

  getPreferences: async (): Promise<UserPreferences> => {
    const res = await userApiClient.get<{ data: UserPreferences }>('/preferences');
    return res.data.data;
  },

  updatePreferences: async (
    data: Partial<UserPreferences>
  ): Promise<UserPreferences> => {
    const res = await userApiClient.put<{ data: UserPreferences }>('/preferences', data);
    return res.data.data;
  },
};
