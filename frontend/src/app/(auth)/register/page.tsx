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
import { Input } from "@/components/ui/input";
import { PasswordInput } from "@/components/ui/password-input";
import { authApi } from "@/lib/api/auth";
import { useSubmitError } from "@/hooks/use-submit-error";
import {
  PASSWORD_HINT,
  PASSWORD_MISMATCH,
  passwordSchema,
} from "@/lib/validation/password";

const schema = z
  .object({
    email: z
      .string()
      .min(1, { message: "Vui lòng nhập email." })
      .email({ message: "Email chưa đúng định dạng." }),
    password: passwordSchema,
    confirm_password: z.string().min(1, { message: "Vui lòng nhập lại mật khẩu." }),
  })
  .refine((values) => values.password === values.confirm_password, {
    message: PASSWORD_MISMATCH,
    path: ["confirm_password"],
  });

type RegisterValues = z.infer<typeof schema>;
const FIELDS = ["email", "password", "confirm_password"] as const;

export default function RegisterPage() {
  const router = useRouter();
  const error = useSubmitError();

  const form = useForm<RegisterValues>({
    resolver: zodResolver(schema),
    defaultValues: { email: "", password: "", confirm_password: "" },
  });

  async function onSubmit(values: RegisterValues) {
    error.reset();
    try {
      await authApi.register(values);
      // Tài khoản ở trạng thái PENDING_VERIFICATION; bước tiếp theo là nhập OTP.
      router.push(`/verify-email?email=${encodeURIComponent(values.email)}`);
    } catch (submitError) {
      error.capture(submitError, {
        fields: FIELDS,
        setFieldError: (field, message) => form.setError(field, { message }),
      });
    }
  }

  const isSubmitting = form.formState.isSubmitting;

  return (
    <div className="space-y-8">
      <header className="space-y-2">
        <h1 className="text-headline-3">Tạo tài khoản</h1>
        <p className="text-[15px] text-muted-foreground">
          Chỉ mất một phút. Chúng tôi sẽ gửi mã xác minh tới email của bạn.
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
                <FormLabel>Mật khẩu</FormLabel>
                <FormControl>
                  <PasswordInput autoComplete="new-password" {...field} />
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
                <FormLabel>Nhập lại mật khẩu</FormLabel>
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
                Đang tạo tài khoản
              </>
            ) : (
              "Tạo tài khoản"
            )}
          </Button>
        </form>
      </Form>

      <p className="text-center text-[14px] text-muted-foreground">
        Đã có tài khoản?{" "}
        <Link
          href="/login"
          className="focus-ring rounded-sm font-medium text-clay-strong hover:underline"
        >
          Đăng nhập
        </Link>
      </p>
    </div>
  );
}
