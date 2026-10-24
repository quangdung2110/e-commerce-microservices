"use client";

import { useForm, useWatch } from "react-hook-form";
import { useState } from "react";
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
import { NativeSelect } from "@/components/ui/native-select";
import { findDistrict, findProvince, findWard, sameAdministrativeCode, VIETNAM_ADDRESS_DATA } from "@/lib/constants/address";
import { toE164 } from "@/lib/format";
import { useSubmitError } from "@/hooks/use-submit-error";
import type { Address, AddressWrite } from "@/lib/api/user";

/**
 * Số điện thoại nội địa Việt Nam: 10 chữ số bắt đầu bằng 0, hoặc đã ở dạng
 * E.164. Server chỉ chấp nhận E.164 và **không** tự thêm mã quốc gia, nên
 * `toE164` chuyển đổi trước khi gửi.
 */
const phoneSchema = z
  .string()
  .min(1, { message: "Vui lòng nhập số điện thoại." })
  .refine((value) => /^\+[1-9]\d{7,14}$/.test(toE164(value)), {
    message: "Số điện thoại chưa hợp lệ. Ví dụ: 0912 345 678.",
  });

const administrativeCode = z.string().trim().regex(/^[A-Za-z0-9_-]{1,20}$/, { message: "Mã địa chỉ gồm 1–20 chữ, số, dấu gạch ngang hoặc gạch dưới." });
const schema = z.object({
  recipient_name: z
    .string()
    .trim()
    .min(1, { message: "Vui lòng nhập tên người nhận." })
    .refine(value => [...value].length <= 200, { message: "Tối đa 200 ký tự." }),
  recipient_phone: phoneSchema,
  province_code: administrativeCode,
  district_code: administrativeCode,
  ward_code: administrativeCode,
  address_line: z
    .string()
    .trim()
    .min(1, { message: "Vui lòng nhập số nhà và tên đường." })
    .refine(value => [...value].length <= 500, { message: "Tối đa 500 ký tự." }),
});

type AddressValues = z.infer<typeof schema>;
const FIELDS = [
  "recipient_name",
  "recipient_phone",
  "province_code",
  "district_code",
  "ward_code",
  "address_line",
] as const;

const EMPTY: AddressValues = {
  recipient_name: "",
  recipient_phone: "",
  province_code: "",
  district_code: "",
  ward_code: "",
  address_line: "",
};

interface AddressFormProps {
  /** Có giá trị nghĩa là đang sửa; không có nghĩa là đang thêm mới. */
  address?: Address | null;
  onSubmit: (input: AddressWrite) => Promise<unknown>;
  onCancel: () => void;
}

export function AddressForm({ address, onSubmit, onCancel }: AddressFormProps) {
  const error = useSubmitError();
  const [manualCodes, setManualCodes] = useState(() => Boolean(address && !findWard(address.province_code, address.district_code, address.ward_code)));

  const form = useForm<AddressValues>({
    resolver: zodResolver(schema),
    defaultValues: address
      ? {
          recipient_name: address.recipient_name,
          recipient_phone: address.recipient_phone,
          province_code: address.province_code,
          district_code: address.district_code,
          ward_code: address.ward_code,
          address_line: address.address_line,
        }
      : EMPTY,
  });

  const [provinceCode, districtCode, wardCode] = useWatch({ control: form.control, name: ["province_code", "district_code", "ward_code"] });

  const province = findProvince(provinceCode);
  const district = findDistrict(provinceCode, districtCode);

  async function handleSubmit(values: AddressValues) {
    error.reset();
    try {
      await onSubmit({ ...values, recipient_phone: toE164(values.recipient_phone) });
    } catch (submitError) {
      error.capture(submitError, {
        fields: FIELDS,
        setFieldError: (field, message) => form.setError(field, { message }),
      });
    }
  }

  const isSaving = form.formState.isSubmitting;

  return (
    <Form {...form}>
      <form onSubmit={form.handleSubmit(handleSubmit)} noValidate>
        <Card>
          <CardHeader>
            <CardTitle>{address ? "Sửa địa chỉ" : "Thêm địa chỉ mới"}</CardTitle>
            <CardDescription>
              Địa chỉ này sẽ được dùng làm nơi giao hàng khi bạn đặt đơn.
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
                name="recipient_name"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Người nhận</FormLabel>
                    <FormControl>
                      <Input autoComplete="name" placeholder="Nguyễn Văn A" {...field} />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />

              <FormField
                control={form.control}
                name="recipient_phone"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Số điện thoại</FormLabel>
                    <FormControl>
                      <Input
                        type="tel"
                        inputMode="tel"
                        autoComplete="tel"
                        placeholder="0912 345 678"
                        {...field}
                      />
                    </FormControl>
                    <FormDescription>
                      Số nội địa sẽ tự được lưu dưới dạng quốc tế (+84…).
                    </FormDescription>
                    <FormMessage />
                  </FormItem>
                )}
              />
            </div>

            <div className="space-y-3">
              <p className="text-caption text-muted-foreground">Danh mục địa chỉ tỉnh–quận/huyện–phường/xã trước tháng 7/2025.</p>
              <label className="flex min-h-11 items-center gap-3 text-[14px]"><input type="checkbox" checked={manualCodes} disabled={isSaving} onChange={event => setManualCodes(event.target.checked)} className="focus-ring size-4 accent-clay" />Nhập mã địa chỉ ngoài danh mục</label>
              {manualCodes && <Alert variant="info">Giữ nguyên mã địa chỉ đã có hoặc nhập mã hành chính của nơi nhận. Ghi rõ tên tỉnh, quận/huyện và phường/xã trong địa chỉ cụ thể nếu danh mục chưa có.</Alert>}
            </div>
            {manualCodes ? <div className="grid gap-5 sm:grid-cols-3">{([
              ["province_code", "Mã tỉnh/thành phố"], ["district_code", "Mã quận/huyện"], ["ward_code", "Mã phường/xã"],
            ] as const).map(([name, label]) => <FormField key={name} control={form.control} name={name} render={({ field }) => <FormItem><FormLabel>{label}</FormLabel><FormControl><Input {...field} disabled={isSaving} maxLength={20} /></FormControl><FormMessage /></FormItem>} />)}</div> : <div className="grid gap-5 sm:grid-cols-3">
              <FormField
                control={form.control}
                name="province_code"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Tỉnh/Thành phố</FormLabel>
                    <FormControl>
                      <NativeSelect
                        {...field}
                        onChange={(event) => {
                          // Đổi tỉnh làm quận và phường đang chọn trở nên vô nghĩa.
                          field.onChange(event);
                          form.setValue("district_code", "", { shouldDirty: true });
                          form.setValue("ward_code", "", { shouldDirty: true });
                        }}
                      >
                        <option value="">Chọn tỉnh/thành</option>
                        {provinceCode && !province && <option value={provinceCode}>Địa chỉ đã lưu: {provinceCode}</option>}
                        {VIETNAM_ADDRESS_DATA.map((item) => (
                          <option key={item.id} value={sameAdministrativeCode(item.id, provinceCode) ? provinceCode : item.id}>
                            {item.name}
                          </option>
                        ))}
                      </NativeSelect>
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />

              <FormField
                control={form.control}
                name="district_code"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Quận/Huyện</FormLabel>
                    <FormControl>
                      <NativeSelect
                        {...field}
                        disabled={!province}
                        onChange={(event) => {
                          field.onChange(event);
                          form.setValue("ward_code", "", { shouldDirty: true });
                        }}
                      >
                        <option value="">
                          {province ? "Chọn quận/huyện" : "Chọn tỉnh trước"}
                        </option>
                        {districtCode && !district && <option value={districtCode}>Địa chỉ đã lưu: {districtCode}</option>}
                        {province?.districts.map((item) => (
                          <option key={item.id} value={sameAdministrativeCode(item.id, districtCode) ? districtCode : item.id}>
                            {item.name}
                          </option>
                        ))}
                      </NativeSelect>
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />

              <FormField
                control={form.control}
                name="ward_code"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Phường/Xã</FormLabel>
                    <FormControl>
                      <NativeSelect {...field} disabled={!district}>
                        <option value="">
                          {district ? "Chọn phường/xã" : "Chọn quận trước"}
                        </option>
                        {wardCode && !findWard(provinceCode, districtCode, wardCode) && <option value={wardCode}>Địa chỉ đã lưu: {wardCode}</option>}
                        {district?.wards.map((item) => (
                          <option key={item.id} value={sameAdministrativeCode(item.id, field.value) ? field.value : item.id}>
                            {item.name}
                          </option>
                        ))}
                      </NativeSelect>
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
              {district && district.wards.length === 0 && <p className="text-caption text-muted-foreground sm:col-span-3">Địa phương này chưa có phường/xã trong danh mục. Chọn “Nhập mã địa chỉ ngoài danh mục” để tiếp tục.</p>}
            </div>}

            <FormField
              control={form.control}
              name="address_line"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Địa chỉ cụ thể</FormLabel>
                  <FormControl>
                    <Input
                      autoComplete="street-address"
                      placeholder="Số nhà, tên đường, toà nhà…"
                      {...field}
                    />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />
          </CardContent>

          <CardFooter>
            <Button type="button" variant="ghost" onClick={onCancel} disabled={isSaving}>
              Huỷ
            </Button>
            <Button type="submit" disabled={isSaving}>
              {isSaving ? (
                <>
                  <LoaderCircle className="animate-spin" aria-hidden />
                  Đang lưu
                </>
              ) : address ? (
                "Lưu địa chỉ"
              ) : (
                "Thêm địa chỉ"
              )}
            </Button>
          </CardFooter>
        </Card>
      </form>
    </Form>
  );
}
