"use client";

import { useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import * as z from "zod";
import { LoaderCircle } from "lucide-react";

import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import {
  Form,
  FormControl,
  FormDescription,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/components/ui/form";
import { Input } from "@/components/ui/input";
import { PasswordInput } from "@/components/ui/password-input";
import { useAuth } from "@/hooks/use-auth";
import { useSubmitError } from "@/hooks/use-submit-error";
import { authApi } from "@/lib/api/auth";

const requestSchema = z.object({
  new_email: z
    .string()
    .min(1, { message: "Vui lòng nhập email mới." })
    .email({ message: "Email chưa đúng định dạng." }),
  current_password: z.string().min(1, { message: "Vui lòng nhập mật khẩu hiện tại." }),
});

// Auth contract: OTP đúng sáu chữ số.
const confirmSchema = z.object({
  otp: z.string().regex(/^\d{6}$/, { message: "Mã xác minh gồm đúng 6 chữ số." }),
});

type RequestValues = z.infer<typeof requestSchema>;
type ConfirmValues = z.infer<typeof confirmSchema>;
const REQUEST_FIELDS = ["new_email", "current_password"] as const;
const CONFIRM_FIELDS = ["otp"] as const;

/**
 * Đổi email đăng nhập, hai bước:
 *
 * 1. Xác thực bằng mật khẩu hiện tại; Auth gửi OTP tới **email mới**. Email chưa
 *    đổi ở bước này.
 * 2. Nhập OTP. Thành công thì Auth đổi email và thu hồi mọi phiên — người dùng
 *    được đưa về trang đăng nhập với email mới điền sẵn.
 */
export function EmailChangeCard() {
  const { email } = useAuth();
  const [pendingEmail, setPendingEmail] = useState<string | null>(null);

  return (
    <Card>
      <CardHeader>
        <CardTitle>Email đăng nhập</CardTitle>
        <CardDescription>
          {email ? (
            <>
              Đang dùng <span className="font-medium text-foreground">{email}</span>.{" "}
            </>
          ) : null}
          Đổi email xong bạn sẽ được đăng xuất khỏi mọi thiết bị.
        </CardDescription>
      </CardHeader>

      {pendingEmail ? (
        <ConfirmStep newEmail={pendingEmail} onBack={() => setPendingEmail(null)} />
      ) : (
        <RequestStep onRequested={setPendingEmail} />
      )}
    </Card>
  );
}

function RequestStep({ onRequested }: { onRequested: (newEmail: string) => void }) {
  const error = useSubmitError();
  const form = useForm<RequestValues>({
    resolver: zodResolver(requestSchema),
    defaultValues: { new_email: "", current_password: "" },
  });

  async function onSubmit(values: RequestValues) {
    error.reset();
    try {
      await authApi.requestEmailChange(values.current_password, values.new_email);
      onRequested(values.new_email);
    } catch (submitError) {
      error.capture(submitError, {
        fields: REQUEST_FIELDS,
        setFieldError: (field, message) => form.setError(field, { message }),
        overrides: {
          INVALID_CREDENTIALS: "Mật khẩu hiện tại không đúng.",
          EMAIL_ALREADY_REGISTERED: "Email này đã thuộc về một tài khoản khác.",
        },
      });
    }
  }

  const isSubmitting = form.formState.isSubmitting;

  return (
    <Form {...form}>
      <form onSubmit={form.handleSubmit(onSubmit)} noValidate>
        <CardContent className="space-y-5">
          {error.message && (
            <Alert variant="danger" traceId={error.traceId}>
              {error.message}
              {error.isCoolingDown && ` Thử lại sau ${error.cooldown} giây.`}
            </Alert>
          )}

          <FormField
            control={form.control}
            name="new_email"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Email mới</FormLabel>
                <FormControl>
                  <Input type="email" inputMode="email" autoComplete="email" {...field} />
                </FormControl>
                <FormMessage />
              </FormItem>
            )}
          />

          <FormField
            control={form.control}
            name="current_password"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Mật khẩu hiện tại</FormLabel>
                <FormControl>
                  <PasswordInput autoComplete="current-password" {...field} />
                </FormControl>
                <FormMessage />
              </FormItem>
            )}
          />
        </CardContent>

        <CardFooter>
          <Button type="submit" disabled={isSubmitting || error.isCoolingDown}>
            {isSubmitting ? (
              <>
                <LoaderCircle className="animate-spin" aria-hidden />
                Đang gửi mã
              </>
            ) : (
              "Gửi mã xác minh"
            )}
          </Button>
        </CardFooter>
      </form>
    </Form>
  );
}

function ConfirmStep({ newEmail, onBack }: { newEmail: string; onBack: () => void }) {
  const error = useSubmitError();
  const form = useForm<ConfirmValues>({
    resolver: zodResolver(confirmSchema),
    defaultValues: { otp: "" },
  });

  async function onSubmit(values: ConfirmValues) {
    error.reset();
    try {
      // Thành công: phiên kết thúc, RequireAuth đưa người dùng về trang đăng nhập.
      await authApi.confirmEmailChange(newEmail, values.otp);
    } catch (submitError) {
      error.capture(submitError, {
        fields: CONFIRM_FIELDS,
        setFieldError: (field, message) => form.setError(field, { message }),
      });
    }
  }

  const isSubmitting = form.formState.isSubmitting;

  return (
    <Form {...form}>
      <form onSubmit={form.handleSubmit(onSubmit)} noValidate>
        <CardContent className="space-y-5">
          <Alert variant="info">
            Đã gửi mã xác minh tới <span className="font-medium">{newEmail}</span>. Mã chỉ
            dùng được một lần và có hạn sử dụng.
          </Alert>

          {error.message && (
            <Alert variant="danger" traceId={error.traceId}>
              {error.message}
              {error.isCoolingDown && ` Thử lại sau ${error.cooldown} giây.`}
            </Alert>
          )}

          <FormField
            control={form.control}
            name="otp"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Mã xác minh</FormLabel>
                <FormControl>
                  <Input
                    inputMode="numeric"
                    autoComplete="one-time-code"
                    maxLength={6}
                    className="max-w-40 font-mono tracking-[0.3em]"
                    {...field}
                  />
                </FormControl>
                <FormDescription>Không nhận được mã? Quay lại để gửi lại.</FormDescription>
                <FormMessage />
              </FormItem>
            )}
          />
        </CardContent>

        <CardFooter className="gap-3">
          <Button type="submit" disabled={isSubmitting || error.isCoolingDown}>
            {isSubmitting ? (
              <>
                <LoaderCircle className="animate-spin" aria-hidden />
                Đang xác minh
              </>
            ) : (
              "Xác nhận đổi email"
            )}
          </Button>
          <Button type="button" variant="ghost" onClick={onBack} disabled={isSubmitting}>
            Quay lại
          </Button>
        </CardFooter>
      </form>
    </Form>
  );
}
