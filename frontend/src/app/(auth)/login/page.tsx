import { Suspense } from "react";
import type { Metadata } from "next";

import { LoginForm } from "@/components/auth/login-form";
import { AuthFormSkeleton } from "@/components/auth/auth-form-skeleton";

export const metadata: Metadata = {
  title: "Đăng nhập",
  description: "Đăng nhập vào tài khoản TechNova của bạn.",
};

export default function LoginPage() {
  // useSearchParams (đọc ?next=) buộc phần form phải nằm sau một Suspense
  // boundary, nếu không `next build` từ chối prerender route này.
  return (
    <Suspense fallback={<AuthFormSkeleton fields={2} />}>
      <LoginForm />
    </Suspense>
  );
}
