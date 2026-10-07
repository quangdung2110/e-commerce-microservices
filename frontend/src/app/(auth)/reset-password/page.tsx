"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import * as z from "zod";
import { LoaderCircle } from "lucide-react";

import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import {
  Form,
  FormControl,
  FormDescription,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/components/ui/form";
import { PasswordInput } from "@/components/ui/password-input";
import { Skeleton } from "@/components/ui/skeleton";
import { authApi } from "@/lib/api/auth";
import { useLocationHashValue } from "@/hooks/use-client-value";
import { useSubmitError } from "@/hooks/use-submit-error";
import {
  PASSWORD_HINT,
  PASSWORD_MISMATCH,
  passwordSchema,
} from "@/lib/validation/password";

const schema = z
  .object({
    password: passwordSchema,
    confirm_password: z.string().min(1, { message: "Vui lòng nhập lại mật khẩu." }),
  })
  .refine((values) => values.password === values.confirm_password, {
    message: PASSWORD_MISMATCH,
    path: ["confirm_password"],
  });

type ResetValues = z.infer<typeof schema>;

/**
 * Auth Service phát liên kết dạng `/reset-password#token=…` — token nằm trong
 * fragment chứ không phải query string, nên nó không bao giờ đi vào log của
 * server hay header Referer. Vì vậy phải đọc bằng `location.hash`.
 *
 * Vẫn chấp nhận `?token=` để một liên kết cũ hoặc dán tay không bị vứt bỏ.
 */
function readResetToken(): string | null {
  const fromHash = new URLSearchParams(window.location.hash.replace(/^#/, ""));
  const fromQuery = new URLSearchParams(window.location.search);
  return fromHash.get("token") ?? fromQuery.get("token");
}

export default function ResetPasswordPage() {
  const router = useRouter();
  const error = useSubmitError();
  // location chỉ tồn tại ở client, nên trạng thái "chưa biết có token không"
  // (`undefined`) phải được render riêng thay vì đoán bừa là không có (`null`).
  const hashToken = useLocationHashValue(readResetToken);
  const ready = hashToken !== undefined;
  const token = hashToken ?? null;

  const form = useForm<ResetValues>({
    resolver: zodResolver(schema),
    defaultValues: { password: "", confirm_password: "" },
  });

  async function onSubmit(values: ResetValues) {
    if (!token) return;
    error.reset();
    try {
      await authApi.completePasswordReset({
        reset_token: token,
        new_password: values.password,
        confirm_password: values.confirm_password,
      });
      router.replace("/login?reset=1");
    } catch (submitError) {
      error.capture(submitError, {
        fields: ["password", "confirm_password"] as const,
        setFieldError: (field, message) => form.setError(field, { message }),
      });
    }
  }

  if (!ready) {
    return (
      <div className="space-y-8">
        <Skeleton className="h-8 w-52" />
        <Skeleton className="h-11 w-full rounded-lg" />
        <Skeleton className="h-11 w-full rounded-lg" />
      </div>
    );
  }

  if (!token) {
    return (
      <div className="space-y-8">
        <header className="space-y-2">
          <h1 className="text-headline-3">Liên kết không hợp lệ</h1>
          <p className="text-[15px] leading-relaxed text-muted-foreground">
            Liên kết đặt lại mật khẩu thiếu mã xác thực, hoặc đã bị cắt ngắn khi
            sao chép. Hãy yêu cầu một liên kết mới.
          </p>
        </header>

        <Link href="/forgot-password" className="block">
          <Button size="lg" className="w-full">
            Yêu cầu liên kết mới
          </Button>
        </Link>
      </div>
    );
  }

  const isSubmitting = form.formState.isSubmitting;

  return (
    <div className="space-y-8">
      <header className="space-y-2">
        <h1 className="text-headline-3">Đặt mật khẩu mới</h1>
        <p className="text-[15px] text-muted-foreground">
          Sau khi đổi, mọi thiết bị đang đăng nhập sẽ bị đăng xuất.
        </p>
      </header>

      {error.message && (
        <Alert variant="danger" traceId={error.traceId}>
          {error.message}
          {error.isCoolingDown && ` Thử lại sau ${error.cooldown} giây.`}
        </Alert>
      )}

      <Form {...form}>
        <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-5" noValidate>
          <FormField
            control={form.control}
            name="password"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Mật khẩu mới</FormLabel>
                <FormControl>
                  <PasswordInput autoComplete="new-password" autoFocus {...field} />
                </FormControl>
                <FormDescription>{PASSWORD_HINT}</FormDescription>
                <FormMessage />
              </FormItem>
            )}
          />

          <FormField
            control={form.control}
            name="confirm_password"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Nhập lại mật khẩu mới</FormLabel>
                <FormControl>
                  <PasswordInput autoComplete="new-password" {...field} />
                </FormControl>
                <FormMessage />
              </FormItem>
            )}
          />

          <Button
            type="submit"
            size="lg"
            className="w-full"
            disabled={isSubmitting || error.isCoolingDown}
          >
            {isSubmitting ? (
              <>
                <LoaderCircle className="animate-spin" aria-hidden />
                Đang đặt lại
              </>
            ) : (
              "Đặt lại mật khẩu"
            )}
          </Button>
        </form>
      </Form>

      <p className="text-center text-[14px] text-muted-foreground">
        <Link
          href="/login"
          className="focus-ring rounded-sm font-medium text-clay-strong hover:underline"
        >
          Quay lại đăng nhập
        </Link>
      </p>
    </div>
  );
}
