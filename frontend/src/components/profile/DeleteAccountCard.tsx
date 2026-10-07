"use client";

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
import { useSubmitError } from "@/hooks/use-submit-error";
import { authApi } from "@/lib/api/auth";

/** Cụm từ người dùng phải gõ lại — buộc đọc kỹ trước một thao tác không hoàn tác. */
const CONFIRM_PHRASE = "XOÁ TÀI KHOẢN";

const schema = z.object({
  current_password: z.string().min(1, { message: "Vui lòng nhập mật khẩu hiện tại." }),
  confirmation: z.string().refine((value) => value.trim() === CONFIRM_PHRASE, {
    message: `Gõ chính xác "${CONFIRM_PHRASE}" để xác nhận.`,
  }),
});

type DeleteValues = z.infer<typeof schema>;
const FIELDS = ["current_password"] as const;

/**
 * Xoá tài khoản theo GDPR. Auth anonymize ngay, thu hồi mọi phiên và hard delete
 * sau 90 ngày; không có đường khôi phục. Thành công thì phiên local kết thúc và
 * người dùng được đưa về trang đăng nhập với lời chào tạm biệt.
 */
export function DeleteAccountCard() {
  const error = useSubmitError();
  const form = useForm<DeleteValues>({
    resolver: zodResolver(schema),
    defaultValues: { current_password: "", confirmation: "" },
  });

  async function onSubmit(values: DeleteValues) {
    error.reset();
    try {
      await authApi.requestOwnDeletion(values.current_password);
    } catch (submitError) {
      error.capture(submitError, {
        fields: FIELDS,
        setFieldError: (field, message) => form.setError(field, { message }),
        overrides: { INVALID_CREDENTIALS: "Mật khẩu hiện tại không đúng." },
      });
    }
  }

  const isSubmitting = form.formState.isSubmitting;

  return (
    <Form {...form}>
      <form onSubmit={form.handleSubmit(onSubmit)} noValidate>
        <Card className="border-danger-border">
          <CardHeader>
            <CardTitle>Xoá tài khoản</CardTitle>
            <CardDescription>
              Tài khoản bị ẩn danh ngay lập tức, mọi thiết bị bị đăng xuất và dữ liệu bị
              xoá hẳn sau 90 ngày. Thao tác này không thể hoàn tác.
            </CardDescription>
          </CardHeader>

          <CardContent className="space-y-5">
            {error.message && (
              <Alert variant="danger" traceId={error.traceId}>
                {error.message}
              </Alert>
            )}

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

            <FormField
              control={form.control}
              name="confirmation"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Xác nhận</FormLabel>
                  <FormControl>
                    <Input autoComplete="off" spellCheck={false} {...field} />
                  </FormControl>
                  <FormDescription>
                    Gõ <span className="font-mono font-medium">{CONFIRM_PHRASE}</span> để
                    xác nhận.
                  </FormDescription>
                  <FormMessage />
                </FormItem>
              )}
            />
          </CardContent>

          <CardFooter>
            <Button type="submit" variant="destructive" disabled={isSubmitting}>
              {isSubmitting ? (
                <>
                  <LoaderCircle className="animate-spin" aria-hidden />
                  Đang xoá
                </>
              ) : (
                "Xoá vĩnh viễn tài khoản"
              )}
            </Button>
          </CardFooter>
        </Card>
      </form>
    </Form>
  );
}
