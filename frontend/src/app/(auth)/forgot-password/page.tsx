"use client";

import { useState } from "react";
import Link from "next/link";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import * as z from "zod";
import { LoaderCircle, MailCheck } from "lucide-react";

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
});

type ForgotValues = z.infer<typeof schema>;

export default function ForgotPasswordPage() {
  const error = useSubmitError();
  const [sentTo, setSentTo] = useState<string | null>(null);

  const form = useForm<ForgotValues>({
    resolver: zodResolver(schema),
    defaultValues: { email: "" },
  });

  async function onSubmit(values: ForgotValues) {
    error.reset();
    try {
      await authApi.requestPasswordReset(values.email);
      setSentTo(values.email);
    } catch (submitError) {
      error.capture(submitError);
    }
  }

  // Backend cố tình trả về như nhau dù email có tồn tại hay không, để không ai
  // dùng form này dò xem địa chỉ nào đã đăng ký. Màn hình thành công phải nói
  // đúng điều đó, không hứa chắc chắn là "email đã được gửi".
  if (sentTo) {
    return (
      <div className="space-y-8">
        <div className="grid size-12 place-content-center rounded-xl bg-success-surface text-success">
          <MailCheck className="size-6" aria-hidden />
        </div>

        <header className="space-y-2">
          <h1 className="text-headline-3">Kiểm tra hộp thư</h1>
          <p className="text-[15px] leading-relaxed text-muted-foreground">
            Nếu <span className="font-medium text-foreground">{sentTo}</span> có
            tài khoản tại TechNova, chúng tôi vừa gửi tới đó một liên kết đặt lại
            mật khẩu. Liên kết chỉ dùng được một lần và sẽ hết hạn sau ít phút.
          </p>
        </header>

        <div className="space-y-3">
          <Button
            variant="outline"
            size="lg"
            className="w-full"
            onClick={() => setSentTo(null)}
          >
            Gửi lại cho email khác
          </Button>
          <Link
            href="/login"
            className="focus-ring block rounded-sm text-center text-[14px] font-medium text-clay-strong hover:underline"
          >
            Quay lại đăng nhập
          </Link>
        </div>
      </div>
    );
  }

  const isSubmitting = form.formState.isSubmitting;

  return (
    <div className="space-y-8">
      <header className="space-y-2">
        <h1 className="text-headline-3">Quên mật khẩu</h1>
        <p className="text-[15px] text-muted-foreground">
          Nhập email của bạn, chúng tôi sẽ gửi liên kết để đặt lại mật khẩu.
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

          <Button
            type="submit"
            size="lg"
            className="w-full"
            disabled={isSubmitting || error.isCoolingDown}
          >
            {isSubmitting ? (
              <>
                <LoaderCircle className="animate-spin" aria-hidden />
                Đang gửi
              </>
            ) : (
              "Gửi liên kết đặt lại"
            )}
          </Button>
        </form>
      </Form>

      <p className="text-center text-[14px] text-muted-foreground">
        Nhớ ra mật khẩu rồi?{" "}
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
