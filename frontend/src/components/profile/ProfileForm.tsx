"use client";

import { useEffect, useState } from "react";
import { useForm } from "react-hook-form";
import { UserProfile } from "@/services/user/api";
import { useProfile, useUpdateProfile } from "@/hooks/use-user";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { useToast } from "@/components/ui/toast";
import { Skeleton } from "@/components/ui/skeleton";

export function ProfileForm() {
  const { toast } = useToast();
  
  const { data: profileData, isLoading, isError } = useProfile();
  const updateProfileMutation = useUpdateProfile();

  const { register, handleSubmit, setValue, formState: { errors } } = useForm<UserProfile>();
  const [gender, setGender] = useState<UserProfile["gender"]>("UNKNOWN");

  useEffect(() => {
    if (profileData) {
      setValue("first_name", profileData.first_name || "");
      setValue("last_name", profileData.last_name || "");
      setValue("phone_number", profileData.phone_number || "");
      if (profileData.date_of_birth) {
        // Backend returns ISO string or date string, format it to YYYY-MM-DD for input type="date"
        try {
          const date = new Date(profileData.date_of_birth);
          if (!isNaN(date.getTime())) {
            setValue("date_of_birth", date.toISOString().split("T")[0]);
          }
        } catch {
          setValue("date_of_birth", profileData.date_of_birth);
        }
      }
      setGender(profileData.gender || "UNKNOWN");
    }
  }, [profileData, setValue]);

  useEffect(() => {
    if (isError) {
      toast({
        title: "Lỗi tải dữ liệu",
        description: "Không thể tải thông tin hồ sơ của bạn.",
        variant: "destructive",
      });
    }
  }, [isError, toast]);

  const onSubmit = (data: UserProfile) => {
    updateProfileMutation.mutate(
      { ...data, gender },
      {
        onSuccess: () => {
          toast({
            title: "Thành công",
            description: "Hồ sơ của bạn đã được cập nhật.",
          });
        },
        onError: () => {
          toast({
            title: "Lỗi cập nhật",
            description: "Đã có lỗi xảy ra khi lưu thông tin.",
            variant: "destructive",
          });
        },
      }
    );
  };

  if (isLoading) {
    return (
      <div className="space-y-6 animate-pulse">
        <div className="grid gap-4 md:grid-cols-2">
          <Skeleton className="h-10 w-full bg-zinc-800/50 rounded-lg" />
          <Skeleton className="h-10 w-full bg-zinc-800/50 rounded-lg" />
        </div>
        <Skeleton className="h-10 w-full bg-zinc-800/50 rounded-lg" />
        <Skeleton className="h-10 w-full bg-zinc-800/50 rounded-lg" />
        <Skeleton className="h-10 w-24 bg-zinc-800/50 rounded-lg" />
      </div>
    );
  }

  return (
    <form onSubmit={handleSubmit(onSubmit)} className="space-y-8">
      <div className="grid gap-6 md:grid-cols-2">
        <div className="space-y-2">
          <Label htmlFor="first_name" className="text-zinc-300">Tên <span className="text-red-500">*</span></Label>
          <Input 
            id="first_name" 
            placeholder="Ví dụ: Dũng" 
            className="bg-black/40 border-zinc-700/50 text-white focus-visible:ring-emerald-500/50 focus-visible:border-emerald-500 transition-all"
            {...register("first_name", { required: "Vui lòng nhập tên" })}
          />
          {errors.first_name && <p className="text-sm text-red-500">{errors.first_name.message}</p>}
        </div>

        <div className="space-y-2">
          <Label htmlFor="last_name" className="text-zinc-300">Họ đệm</Label>
          <Input 
            id="last_name" 
            placeholder="Ví dụ: Nguyễn Quang" 
            className="bg-black/40 border-zinc-700/50 text-white focus-visible:ring-emerald-500/50 focus-visible:border-emerald-500 transition-all"
            {...register("last_name")}
          />
        </div>
      </div>

      <div className="space-y-2">
        <Label htmlFor="phone_number" className="text-zinc-300">Số điện thoại</Label>
        <div className="relative">
          <span className="absolute left-3 top-1/2 -translate-y-1/2 text-zinc-500">+84</span>
          <Input 
            id="phone_number" 
            placeholder="987 654 321" 
            className="bg-black/40 border-zinc-700/50 text-white focus-visible:ring-emerald-500/50 focus-visible:border-emerald-500 transition-all pl-12"
            {...register("phone_number")}
          />
        </div>
        <p className="text-xs text-zinc-500">Số điện thoại dùng để liên lạc khi giao hàng.</p>
      </div>

      <div className="grid gap-6 md:grid-cols-2">
        <div className="space-y-2">
          <Label htmlFor="date_of_birth" className="text-zinc-300">Ngày sinh</Label>
          <Input 
            id="date_of_birth" 
            type="date"
            className="bg-black/40 border-zinc-700/50 text-white focus-visible:ring-emerald-500/50 focus-visible:border-emerald-500 transition-all [color-scheme:dark]"
            {...register("date_of_birth")}
          />
        </div>

        <div className="space-y-2">
          <Label className="text-zinc-300">Giới tính</Label>
          <Select value={gender} onValueChange={(val: any) => setGender(val)}>
            <SelectTrigger className="bg-black/40 border-zinc-700/50 text-white focus:ring-emerald-500/50">
              <SelectValue placeholder="Chọn giới tính" />
            </SelectTrigger>
            <SelectContent className="bg-zinc-900 border-zinc-800 text-white">
              <SelectItem value="MALE" className="focus:bg-zinc-800 focus:text-white">Nam</SelectItem>
              <SelectItem value="FEMALE" className="focus:bg-zinc-800 focus:text-white">Nữ</SelectItem>
              <SelectItem value="OTHER" className="focus:bg-zinc-800 focus:text-white">Khác</SelectItem>
              <SelectItem value="UNKNOWN" className="focus:bg-zinc-800 focus:text-white">Không muốn tiết lộ</SelectItem>
            </SelectContent>
          </Select>
        </div>
      </div>

      <div className="pt-4 border-t border-zinc-800/50">
        <Button 
          type="submit" 
          disabled={updateProfileMutation.isPending}
          className="bg-emerald-500 hover:bg-emerald-400 text-black font-semibold shadow-[0_0_20px_rgba(16,185,129,0.3)] hover:shadow-[0_0_30px_rgba(16,185,129,0.5)] transition-all duration-300 px-8"
        >
          {updateProfileMutation.isPending ? "Đang lưu..." : "Lưu thay đổi"}
        </Button>
      </div>
    </form>
  );
}
