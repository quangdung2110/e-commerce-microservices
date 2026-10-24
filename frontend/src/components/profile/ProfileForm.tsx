"use client";

import { useEffect } from "react";
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
import { Label } from "@/components/ui/label";
import { NativeSelect } from "@/components/ui/native-select";
import { Skeleton } from "@/components/ui/skeleton";
import { useToast } from "@/components/ui/toast";
import { useProfile, useUpdateProfile } from "@/hooks/use-user";
import { useSubmitError } from "@/hooks/use-submit-error";
import type { Gender, Profile, ProfilePatch } from "@/lib/api/user";

const GENDER_LABELS: Record<Gender, string> = {
  MALE: "Nam",
  FEMALE: "Nữ",
  OTHER: "Khác",
  UNKNOWN: "Không muốn tiết lộ",
};

// Match the backend's rune count instead of UTF-16 string length.
const nameSchema = z.string().trim().refine((value) => [...value].length <= 100, {
  message: "Tối đa 100 ký tự.",
});

const schema = z.object({
  first_name: nameSchema,
  last_name: nameSchema,
  date_of_birth: z
    .string()
    .refine((value) => value === "" || !Number.isNaN(Date.parse(value)), {
      message: "Ngày sinh chưa hợp lệ.",
    })
    .refine((value) => value === "" || new Date(value) <= new Date(), {
      message: "Ngày sinh không thể ở tương lai.",
    }),
  gender: z.enum(["MALE", "FEMALE", "OTHER", "UNKNOWN"]),
});

type ProfileValues = z.infer<typeof schema>;
const FIELDS = ["first_name", "last_name", "date_of_birth", "gender"] as const;

function toFormValues(profile: Profile): ProfileValues {
  return {
    first_name: profile.first_name ?? "",
    last_name: profile.last_name ?? "",
    date_of_birth: profile.date_of_birth ?? "",
    gender: profile.gender,
  };
}

/**
 * Dựng JSON Merge Patch: chỉ những field thực sự đổi mới được gửi đi.
 *
 * Ô trống nghĩa là "xoá giá trị" nên gửi `null` — trừ `gender`, thứ mà server
 * từ chối `null`; ở đó "không tiết lộ" được biểu diễn bằng `"UNKNOWN"`.
 */
function buildPatch(current: Profile, values: ProfileValues): ProfilePatch {
  const patch: ProfilePatch = {};
  const nullable = (value: string) => (value.trim() === "" ? null : value.trim());

  const firstName = nullable(values.first_name);
  if (firstName !== (current.first_name ?? null)) patch.first_name = firstName;

  const lastName = nullable(values.last_name);
  if (lastName !== (current.last_name ?? null)) patch.last_name = lastName;

  const dateOfBirth = nullable(values.date_of_birth);
  if (dateOfBirth !== (current.date_of_birth ?? null)) patch.date_of_birth = dateOfBirth;

  if (values.gender !== current.gender) patch.gender = values.gender;

  return patch;
}

export function ProfileForm() {
  const { data: profile, isPending, isError, refetch } = useProfile();
  const updateProfile = useUpdateProfile();
  const { addToast } = useToast();
  const error = useSubmitError();

  const form = useForm<ProfileValues>({
    resolver: zodResolver(schema),
    defaultValues: { first_name: "", last_name: "", date_of_birth: "", gender: "UNKNOWN" },
  });

  // Form chỉ được nạp lại khi bản ghi từ server thay đổi, để không đè lên
  // những gì người dùng đang gõ dở.
  useEffect(() => {
    if (profile) form.reset(toFormValues(profile));
  }, [profile, form]);

  async function onSubmit(values: ProfileValues) {
    if (!profile) return;

    const patch = buildPatch(profile, values);
    if (Object.keys(patch).length === 0) {
      // Server từ chối một merge patch rỗng, nên chặn ngay ở đây.
      addToast({ type: "info", title: "Không có gì thay đổi" });
      return;
    }

    error.reset();
    try {
      await updateProfile.mutateAsync(patch);
      addToast({ type: "success", title: "Đã lưu hồ sơ" });
    } catch (submitError) {
      error.capture(submitError, {
        fields: FIELDS,
        setFieldError: (field, message) => form.setError(field, { message }),
      });
    }
  }

  if (isPending) return <ProfileFormSkeleton />;

  if (isError || !profile) {
    return (
      <Card>
        <CardContent className="space-y-4">
          <Alert variant="danger">
            Không tải được hồ sơ của bạn. Vui lòng thử lại.
          </Alert>
          <Button variant="outline" onClick={() => void refetch()}>
            Thử lại
          </Button>
        </CardContent>
      </Card>
    );
  }

  const isSaving = form.formState.isSubmitting || updateProfile.isPending;

  return (
    <Form {...form}>
      <form onSubmit={form.handleSubmit(onSubmit)} noValidate>
        <Card>
          <CardHeader>
            <CardTitle>Thông tin cá nhân</CardTitle>
            <CardDescription>
              Tên hiển thị được ghép tự động từ họ và tên của bạn.
            </CardDescription>
          </CardHeader>

          <CardContent className="space-y-5">
            {error.message && (
              <Alert variant="danger" traceId={error.traceId}>
                {error.message}
              </Alert>
            )}

            <div className="grid gap-5 sm:grid-cols-2">
              <FormField
                control={form.control}
                name="last_name"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Họ</FormLabel>
                    <FormControl>
                      <Input autoComplete="family-name" placeholder="Nguyễn" {...field} />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />

              <FormField
                control={form.control}
                name="first_name"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Tên</FormLabel>
                    <FormControl>
                      <Input autoComplete="given-name" placeholder="Quang Dũng" {...field} />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
            </div>

            {/* Không dùng FormItem/FormLabel ở đây: chúng đọc context của
                FormField, mà ô này không thuộc form — nó chỉ hiển thị giá trị
                server dẫn xuất. */}
            <div className="space-y-2">
              <Label htmlFor="display-name">Tên hiển thị</Label>
              <Input
                id="display-name"
                value={profile.display_name || "—"}
                readOnly
                disabled
                aria-describedby="display-name-hint"
              />
              <p id="display-name-hint" className="text-caption text-muted-foreground">
                Chỉ đọc — hệ thống tự dẫn xuất từ họ và tên.
              </p>
            </div>

            <div className="grid gap-5 sm:grid-cols-2">
              <FormField
                control={form.control}
                name="date_of_birth"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Ngày sinh</FormLabel>
                    <FormControl>
                      <Input type="date" autoComplete="bday" {...field} />
                    </FormControl>
                    <FormDescription>Để trống nếu bạn không muốn cung cấp.</FormDescription>
                    <FormMessage />
                  </FormItem>
                )}
              />

              <FormField
                control={form.control}
                name="gender"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Giới tính</FormLabel>
                    <FormControl>
                      <NativeSelect {...field}>
                        {(Object.keys(GENDER_LABELS) as Gender[]).map((value) => (
                          <option key={value} value={value}>
                            {GENDER_LABELS[value]}
                          </option>
                        ))}
                      </NativeSelect>
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
            </div>
          </CardContent>

          <CardFooter>
            <Button
              type="button"
              variant="ghost"
              onClick={() => form.reset(toFormValues(profile))}
              disabled={isSaving || !form.formState.isDirty}
            >
              Hoàn tác
            </Button>
            <Button type="submit" disabled={isSaving || !form.formState.isDirty}>
              {isSaving ? (
                <>
                  <LoaderCircle className="animate-spin" aria-hidden />
                  Đang lưu
                </>
              ) : (
                "Lưu thay đổi"
              )}
            </Button>
          </CardFooter>
        </Card>
      </form>
    </Form>
  );
}

function ProfileFormSkeleton() {
  return (
    <Card aria-busy="true">
      <CardHeader>
        <Skeleton className="h-6 w-44" />
        <Skeleton className="h-4 w-72 max-w-full" />
      </CardHeader>
      <CardContent className="space-y-5">
        <div className="grid gap-5 sm:grid-cols-2">
          <Skeleton className="h-[68px] w-full" />
          <Skeleton className="h-[68px] w-full" />
        </div>
        <Skeleton className="h-[68px] w-full" />
        <div className="grid gap-5 sm:grid-cols-2">
          <Skeleton className="h-[68px] w-full" />
          <Skeleton className="h-[68px] w-full" />
        </div>
      </CardContent>
    </Card>
  );
}
