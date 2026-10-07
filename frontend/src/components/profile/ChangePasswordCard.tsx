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
    current_password: z.string().min(1, { message: "Vui lòng nhập mật khẩu hiện tại." }),
    new_password: passwordSchema,
    confirm_password: z.string().min(1, { message: "Vui lòng nhập lại mật khẩu mới." }),
  })
  .refine((values) => values.new_password === values.confirm_password, {
    message: PASSWORD_MISMATCH,
    path: ["confirm_password"],
  })
  .refine((values) => values.new_password !== values.current_password, {
    message: "Mật khẩu mới phải khác mật khẩu hiện tại.",
    path: ["new_password"],
  });

type ChangePasswordValues = z.infer<typeof schema>;
const FIELDS = ["current_password", "new_password", "confirm_password"] as const;

/**
 * Đổi mật khẩu — endpoint này thuộc Auth Service, không phải User Service.
 *
 * Auth thu hồi **mọi** phiên khi đổi mật khẩu, kể cả phiên đang dùng. Sau khi
 * thành công, `authApi` kết thúc phiên local và `RequireAuth` đưa người dùng về
 * trang đăng nhập kèm lời giải thích; component không cần tự điều hướng.
 */
export function ChangePasswordCard() {
  const error = useSubmitError();

  const form = useForm<ChangePasswordValues>({
    resolver: zodResolver(schema),
    defaultValues: { current_password: "", new_password: "", confirm_password: "" },
  });

  async function onSubmit(values: ChangePasswordValues) {
    error.reset();
    try {
      await authApi.changePassword(values);
    } catch (submitError) {
      error.capture(submitError, {
        fields: FIELDS,
        setFieldError: (field, message) => form.setError(field, { message }),
        overrides: {
          // Ở ngữ cảnh này, 401 nghĩa là mật khẩu hiện tại sai — không phải phiên hết hạn.
          INVALID_CREDENTIALS: "Mật khẩu hiện tại không đúng.",
        },
      });
    }
  }

  const isSaving = form.formState.isSubmitting;

  return (
    <Form {...form}>
      <form onSubmit={form.handleSubmit(onSubmit)} noValidate>
        <Card>
          <CardHeader>
            <CardTitle>Mật khẩu</CardTitle>
            <CardDescription>
              Đổi mật khẩu sẽ đăng xuất mọi thiết bị, kể cả thiết bị này. Bạn sẽ đăng
              nhập lại bằng mật khẩu mới.
            </CardDescription>
          </CardHeader>

          <CardContent className="space-y-5">
            {error.message && (
              <Alert variant="danger" traceId={error.traceId}>
                {error.message}
                {error.isCoolingDown && ` Thử lại sau ${error.cooldown} giây.`}
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
              name="new_password"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Mật khẩu mới</FormLabel>
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
                  <FormLabel>Nhập lại mật khẩu mới</FormLabel>
                  <FormControl>
                    <PasswordInput autoComplete="new-password" {...field} />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />
          </CardContent>

          <CardFooter>
            <Button type="submit" disabled={isSaving || error.isCoolingDown}>
              {isSaving ? (
                <>
                  <LoaderCircle className="animate-spin" aria-hidden />
                  Đang đổi
                </>
              ) : (
                "Đổi mật khẩu"
              )}
            </Button>
          </CardFooter>
        </Card>
      </form>
    </Form>
  );
}
