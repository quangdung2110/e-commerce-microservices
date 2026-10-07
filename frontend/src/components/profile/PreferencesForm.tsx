"use client";

import { useState } from "react";
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
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";
import { Switch } from "@/components/ui/switch";
import { useToast } from "@/components/ui/toast";
import { usePreferences, useUpdatePreferences } from "@/hooks/use-user";
import { useSubmitError } from "@/hooks/use-submit-error";
import type { PreferencesUpdate } from "@/lib/api/user";

const CHANNELS: {
  key: keyof PreferencesUpdate;
  label: string;
  description: string;
}[] = [
  {
    key: "email_enabled",
    label: "Thông báo qua email",
    description:
      "Xác nhận đơn hàng, cập nhật vận chuyển và các thông báo bảo mật quan trọng.",
  },
  {
    key: "in_app_enabled",
    label: "Thông báo trong ứng dụng",
    description: "Hiển thị ngay trên web khi có cập nhật liên quan tới bạn.",
  },
  {
    key: "marketing_enabled",
    label: "Tin khuyến mãi",
    description: "Ưu đãi, mã giảm giá và sản phẩm mới. Bạn có thể tắt bất cứ lúc nào.",
  },
];

export function PreferencesForm() {
  const { data, isPending, isError, refetch } = usePreferences();
  const updatePreferences = useUpdatePreferences();
  const { addToast } = useToast();
  const error = useSubmitError();

  // Chỉ giữ những cờ người dùng đã bật/tắt mà chưa lưu. Bản nháp đầy đủ được
  // suy ra từ dữ liệu server + các thay đổi này, nên không cần chép `data` vào
  // state mỗi khi query tải xong.
  const [edits, setEdits] = useState<Partial<PreferencesUpdate>>({});

  // Nhánh lỗi phải đứng trước nhánh chờ, nếu không màn hình sẽ kẹt ở skeleton
  // vĩnh viễn khi query lỗi.
  if (isError) {
    return (
      <Card>
        <CardContent className="space-y-4">
          <Alert variant="danger">
            Không tải được tuỳ chọn thông báo. Vui lòng thử lại.
          </Alert>
          <Button variant="outline" onClick={() => void refetch()}>
            Thử lại
          </Button>
        </CardContent>
      </Card>
    );
  }

  if (isPending || !data) return <PreferencesSkeleton />;

  // PUT thay toàn bộ nên bản gửi đi luôn đủ cả ba cờ.
  const draft: PreferencesUpdate = {
    email_enabled: edits.email_enabled ?? data.email_enabled,
    in_app_enabled: edits.in_app_enabled ?? data.in_app_enabled,
    marketing_enabled: edits.marketing_enabled ?? data.marketing_enabled,
  };
  const isDirty = CHANNELS.some(({ key }) => draft[key] !== data[key]);

  async function onSave() {
    error.reset();
    try {
      await updatePreferences.mutateAsync(draft);
      setEdits({});
      addToast({ type: "success", title: "Đã lưu tuỳ chọn thông báo" });
    } catch (submitError) {
      error.capture(submitError);
    }
  }

  return (
    <div className="space-y-8">
      <Card>
        <CardHeader>
          <CardTitle>Tuỳ chọn thông báo</CardTitle>
          <CardDescription>
            Chọn cách chúng tôi liên lạc với bạn. Thông báo bảo mật luôn được gửi
            dù bạn tắt kênh nào.
          </CardDescription>
        </CardHeader>

        <CardContent className="space-y-6">
          {error.message && (
            <Alert variant="danger" traceId={error.traceId}>
              {error.message}
            </Alert>
          )}

          {CHANNELS.map(({ key, label, description }) => (
            <div key={key} className="flex items-start justify-between gap-6">
              <div className="space-y-1">
                <Label htmlFor={key}>{label}</Label>
                <p className="max-w-prose-narrow text-caption text-muted-foreground">
                  {description}
                </p>
              </div>
              <Switch
                id={key}
                checked={draft[key]}
                onCheckedChange={(checked) =>
                  setEdits((current) => ({ ...current, [key]: checked }))
                }
              />
            </div>
          ))}
        </CardContent>

        <CardFooter>
          <Button
            variant="ghost"
            onClick={() => setEdits({})}
            disabled={!isDirty || updatePreferences.isPending}
          >
            Hoàn tác
          </Button>
          <Button
            onClick={() => void onSave()}
            disabled={!isDirty || updatePreferences.isPending}
          >
            {updatePreferences.isPending ? (
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

      <Card>
        <CardHeader>
          <CardTitle>Ngôn ngữ và tiền tệ</CardTitle>
          <CardDescription>
            Hiện hệ thống chỉ hỗ trợ tiếng Việt và đồng Việt Nam.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <dl className="divide-y divide-border">
            <div className="grid gap-1 py-3 first:pt-0 last:pb-0 sm:grid-cols-[180px_1fr] sm:gap-4">
              <dt className="text-[14px] text-muted-foreground">Ngôn ngữ</dt>
              <dd className="text-[15px]">
                {data.language === "vi" ? "Tiếng Việt" : data.language}
              </dd>
            </div>
            <div className="grid gap-1 py-3 first:pt-0 last:pb-0 sm:grid-cols-[180px_1fr] sm:gap-4">
              <dt className="text-[14px] text-muted-foreground">Tiền tệ</dt>
              <dd className="text-[15px]">
                {data.currency === "VND" ? "VND — Đồng Việt Nam" : data.currency}
              </dd>
            </div>
          </dl>
        </CardContent>
      </Card>
    </div>
  );
}

function PreferencesSkeleton() {
  return (
    <Card aria-busy="true">
      <CardHeader>
        <Skeleton className="h-6 w-48" />
        <Skeleton className="h-4 w-80 max-w-full" />
      </CardHeader>
      <CardContent className="space-y-6">
        {[0, 1, 2].map((index) => (
          <div key={index} className="flex items-start justify-between gap-6">
            <div className="space-y-2">
              <Skeleton className="h-4 w-44" />
              <Skeleton className="h-3 w-72 max-w-full" />
            </div>
            <Skeleton className="h-6 w-11 rounded-full" />
          </div>
        ))}
      </CardContent>
    </Card>
  );
}
