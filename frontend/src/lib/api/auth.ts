import axios from 'axios';
import { v4 as uuidv4 } from 'uuid';

const API_BASE_URL = process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8080/api/v1';

// Setup Axios instance
export const authApiClient = axios.create({
  baseURL: `${API_BASE_URL}/auth/public`,
  headers: {
    'Content-Type': 'application/json',
  },
  withCredentials: true, // IMPORTANT: To send/receive HttpOnly cookies (refresh_token)
});

// Helper to get or generate X-Device-ID
export const getDeviceId = (): string => {
  if (typeof window !== 'undefined') {
    let deviceId = localStorage.getItem('deviceId');
    if (!deviceId) {
      deviceId = uuidv4();
      localStorage.setItem('deviceId', deviceId);
    }
    return deviceId;
  }
  return uuidv4();
};

// Add interceptor to inject X-Device-ID
authApiClient.interceptors.request.use((config) => {
  config.headers['X-Device-ID'] = getDeviceId();
  return config;
});

// DTOs based on backend Go structs
export interface LoginRequest {
  email: string;
  password: string;
}

export interface RegisterRequest {
  email: string;
  password: string;
}

export interface VerifyEmailRequest {
  email: string;
  otp: string;
}

export interface ResendOTPRequest {
  email: string;
}

export interface ForgotPasswordRequest {
  email: string;
}

export interface ResetPasswordRequest {
  token: string;
  new_password: string;
}

export interface AuthResponse {
  access_token: string;
  expires_in: number;
  token_type: string;
  user: {
    id: string;
    email: string;
    roles: string[];
    status: string;
  };
}

export const AuthAPI = {
  login: async (data: LoginRequest) => {
    const response = await authApiClient.post<AuthResponse>('/login', data);
    return response.data;
  },

  register: async (data: RegisterRequest) => {
    const response = await authApiClient.post('/register', data);
    return response.data;
  },

  verifyEmail: async (data: VerifyEmailRequest) => {
    const response = await authApiClient.post('/verify-email', data);
    return response.data;
  },

  resendOTP: async (data: ResendOTPRequest) => {
    const response = await authApiClient.post('/resend-otp', data);
    return response.data;
  },

  forgotPassword: async (data: ForgotPasswordRequest) => {
    const response = await authApiClient.post('/forgot-password', data);
    return response.data;
  },

  resetPassword: async (data: ResetPasswordRequest) => {
    const response = await authApiClient.post('/reset-password', data);
    return response.data;
  }
};
