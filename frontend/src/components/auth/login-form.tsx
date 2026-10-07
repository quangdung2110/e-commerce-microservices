"use client";

import { useEffect } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import * as z from "zod";
import { LoaderCircle } from "lucide-react";

import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import {
  Form,
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/components/ui/form";
import { Input } from "@/components/ui/input";
import { PasswordInput } from "@/components/ui/password-input";
import { authApi } from "@/lib/api/auth";
import { useAuth } from "@/hooks/use-auth";
import { useSubmitError } from "@/hooks/use-submit-error";
import { useAuthStore, type SignOutReason } from "@/store/useAuthStore";

/** Lời giải thích khi người dùng bị đưa về trang này sau khi phiên kết thúc. */
const SIGN_OUT_NOTICES: Record<SignOutReason, { variant: "info" | "success"; text: string }> = {
  logged_out: { variant: "info", text: "Bạn đã đăng xuất khỏi thiết bị này." },
  password_changed: {
    variant: "success",
    text: "Đã đổi mật khẩu. Mọi thiết bị đã được đăng xuất — hãy đăng nhập lại bằng mật khẩu mới.",
  },
  email_changed: {
    variant: "success",
    text: "Đã đổi email. Hãy đăng nhập lại bằng email mới.",
  },
  sessions_revoked: {
    variant: "info",
    text: "Đã đăng xuất khỏi mọi thiết bị, kể cả thiết bị này.",
  },
  account_deleted: {
    variant: "info",
    text: "Tài khoản của bạn đã được xoá. Cảm ơn bạn đã dùng TechNova.",
  },
  revoked: {
    variant: "info",
    text: "Phiên đăng nhập của bạn đã bị thu hồi. Vui lòng đăng nhập lại.",
  },
};

const schema = z.object({
  email: z
    .string()
    .min(1, { message: "Vui lòng nhập email." })
    .email({ message: "Email chưa đúng định dạng." }),
  password: z.string().min(1, { message: "Vui lòng nhập mật khẩu." }),
});

type LoginValues = z.infer<typeof schema>;
const FIELDS = ["email", "password"] as const;

export function LoginForm() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const { isAuthenticated, email: rememberedEmail } = useAuth();
  const signOutReason = useAuthStore((state) => state.signOutReason);
  const error = useSubmitError();
  const notice = signOutReason
    ? SIGN_OUT_NOTICES[signOutReason]
    : searchParams.get("verified")
      ? ({ variant: "success", text: "Email đã được xác minh. Bạn có thể đăng nhập." } as const)
      : searchParams.get("reset")
        ? ({ variant: "success", text: "Đã đặt lại mật khẩu. Hãy đăng nhập bằng mật khẩu mới." } as const)
        : null;

  // Chỉ nhận đường dẫn nội bộ. Cả `?next=https://…` lẫn `?next=//evil.com`
  // (URL không có scheme) đều là open redirect, nên loại cả hai.
  const nextParam = searchParams.get("next");
  const redirectTo =
    nextParam && nextParam.startsWith("/") && !nextParam.startsWith("//")
      ? nextParam
      : "/";

  const form = useForm<LoginValues>({
    resolver: zodResolver(schema),
    defaultValues: { email: rememberedEmail ?? "", password: "" },
  });

  // Người đã đăng nhập không có việc gì ở đây — có thể vừa bấm nút back.
  useEffect(() => {
    if (isAuthenticated) router.replace(redirectTo);
  }, [isAuthenticated, redirectTo, router]);

  async function onSubmit(values: LoginValues) {
    error.reset();
    try {
      await authApi.login(values);
      router.replace(redirectTo);
    } catch (submitError) {
      const code = error.capture(submitError, {
        fields: FIELDS,
        setFieldError: (field, message) => form.setError(field, { message }),
      });

      // Chưa xác minh email không phải lỗi đăng nhập, mà là bước còn thiếu.
      if (code === "ACCOUNT_NOT_VERIFIED") {
        router.push(`/verify-email?email=${encodeURIComponent(values.email)}`);
      }
    }
  }

  const isSubmitting = form.formState.isSubmitting;
  const disabled = isSubmitting || error.isCoolingDown;

  return (
    <div className="space-y-8">
      <header className="space-y-2">
        <h1 className="text-headline-3">Đăng nhập</h1>
        <p className="text-[15px] text-muted-foreground">
          Tiếp tục với tài khoản TechNova của bạn.
        </p>
      </header>

      {error.message ? (
        <Alert variant="danger" traceId={error.traceId}>
          {error.message}
          {error.isCoolingDown && ` Thử lại sau ${error.cooldown} giây.`}
        </Alert>
      ) : (
        notice && <Alert variant={notice.variant}>{notice.text}</Alert>
      )}

      <Form {...form}>
        <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-5" noValidate>
          <FormField
            control={form.control}
            name="email"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Email</FormLabel>
                <FormControl>
                  <Input
                    type="email"
                    inputMode="email"
                    autoComplete="email"
                    autoFocus
                    placeholder="ban@example.com"
                    {...field}
                  />
                </FormControl>
                <FormMessage />
              </FormItem>
            )}
          />

          <FormField
            control={form.control}
            name="password"
            render={({ field }) => (
              <FormItem>
                <div className="flex items-baseline justify-between gap-4">
                  <FormLabel>Mật khẩu</FormLabel>
                  <Link
                    href="/forgot-password"
                    className="focus-ring rounded-sm text-caption text-clay-strong hover:underline"
                  >
                    Quên mật khẩu?
                  </Link>
                </div>
                <FormControl>
                  <PasswordInput
                    autoComplete="current-password"
                    placeholder="Nhập mật khẩu"
                    {...field}
                  />
                </FormControl>
                <FormMessage />
              </FormItem>
            )}
          />

          <Button type="submit" size="lg" className="w-full" disabled={disabled}>
            {isSubmitting ? (
              <>
                <LoaderCircle className="animate-spin" aria-hidden />
                Đang đăng nhập
              </>
            ) : error.isCoolingDown ? (
              `Thử lại sau ${error.cooldown}s`
            ) : (
              "Đăng nhập"
            )}
          </Button>
        </form>
      </Form>

      <p className="text-center text-[14px] text-muted-foreground">
        Chưa có tài khoản?{" "}
        <Link
          href="/register"
          className="focus-ring rounded-sm font-medium text-clay-strong hover:underline"
        >
          Đăng ký
        </Link>
      </p>
    </div>
  );
}
