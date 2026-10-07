"use client";

import { useState } from "react";
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
import { authApi } from "@/lib/api/auth";
import { useSubmitError } from "@/hooks/use-submit-error";

const schema = z.object({
  email: z
    .string()
    .min(1, { message: "Vui lòng nhập email." })
    .email({ message: "Email chưa đúng định dạng." }),
  otp: z
    .string()
    .min(1, { message: "Vui lòng nhập mã xác minh." })
    .regex(/^\d{4,8}$/, { message: "Mã xác minh chỉ gồm chữ số." }),
});

type VerifyValues = z.infer<typeof schema>;
const FIELDS = ["email", "otp"] as const;

export function VerifyEmailForm() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const error = useSubmitError();
  const resendError = useSubmitError();
  const [resent, setResent] = useState(false);
  const [resending, setResending] = useState(false);

  const emailParam = searchParams.get("email") ?? "";

  const form = useForm<VerifyValues>({
    resolver: zodResolver(schema),
    defaultValues: { email: emailParam, otp: "" },
  });

  async function onSubmit(values: VerifyValues) {
    error.reset();
    try {
      await authApi.verifyEmail(values);
      router.replace(`/login?verified=1`);
    } catch (submitError) {
      error.capture(submitError, {
        fields: FIELDS,
        setFieldError: (field, message) => form.setError(field, { message }),
      });
    }
  }

  async function onResend() {
    const email = form.getValues("email");
    if (!email) {
      form.setError("email", { message: "Nhập email để nhận lại mã." });
      return;
    }

    resendError.reset();
    setResent(false);
    setResending(true);
    try {
      await authApi.requestEmailVerification(email);
      setResent(true);
    } catch (submitError) {
      resendError.capture(submitError);
    } finally {
      setResending(false);
    }
  }

  const isSubmitting = form.formState.isSubmitting;

  return (
    <div className="space-y-8">
      <header className="space-y-2">
        <h1 className="text-headline-3">Xác minh email</h1>
        <p className="text-[15px] text-muted-foreground">
          {emailParam ? (
            <>
              Chúng tôi đã gửi mã xác minh tới{" "}
              <span className="font-medium text-foreground">{emailParam}</span>.
            </>
          ) : (
            "Nhập email và mã xác minh chúng tôi vừa gửi cho bạn."
          )}
        </p>
      </header>

      {error.message && (
        <Alert variant="danger" traceId={error.traceId}>
          {error.message}
          {error.isCoolingDown && ` Thử lại sau ${error.cooldown} giây.`}
        </Alert>
      )}

      {resendError.message && (
        <Alert variant="danger" traceId={resendError.traceId}>
          {resendError.message}
        </Alert>
      )}

      {resent && (
        <Alert variant="success">
          Đã gửi lại mã xác minh. Vui lòng kiểm tra hộp thư, kể cả mục spam.
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
                    readOnly={Boolean(emailParam)}
                    className={emailParam ? "bg-surface-3" : undefined}
                    {...field}
                  />
                </FormControl>
                <FormMessage />
              </FormItem>
            )}
          />

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
                    autoFocus
                    placeholder="123456"
                    className="text-center font-mono text-headline-6 tracking-[0.4em]"
                    {...field}
                  />
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
                Đang xác minh
              </>
            ) : (
              "Xác minh"
            )}
          </Button>
        </form>
      </Form>

      <div className="space-y-3 text-center text-[14px] text-muted-foreground">
        <p>
          Chưa nhận được mã?{" "}
          <Button
            variant="link"
            size="sm"
            className="h-auto p-0 align-baseline"
            onClick={() => void onResend()}
            disabled={resending || resendError.isCoolingDown}
          >
            {resending
              ? "Đang gửi…"
              : resendError.isCoolingDown
                ? `Gửi lại sau ${resendError.cooldown}s`
                : "Gửi lại mã"}
          </Button>
        </p>
        <p>
          <Link
            href="/login"
            className="focus-ring rounded-sm font-medium text-clay-strong hover:underline"
          >
            Quay lại đăng nhập
          </Link>
        </p>
      </div>
    </div>
  );
}
