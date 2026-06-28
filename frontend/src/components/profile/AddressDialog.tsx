"use client";

import { useState, useEffect } from "react";
import { useForm } from "react-hook-form";
import { UserAddress } from "@/services/user/api";
import { PROVINCES, DISTRICTS, WARDS } from "@/lib/mock-locations";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { X } from "lucide-react";

interface AddressDialogProps {
  isOpen: boolean;
  onClose: () => void;
  onSave: (data: Omit<UserAddress, "id" | "user_id" | "is_default">) => Promise<void>;
  address?: UserAddress | null;
}

type FormData = Omit<UserAddress, "id" | "user_id" | "is_default">;

export function AddressDialog({ isOpen, onClose, onSave, address }: AddressDialogProps) {
  const [saving, setSaving] = useState(false);
  const { register, handleSubmit, setValue, watch, reset, formState: { errors } } = useForm<FormData>();
  
  const selectedCity = watch("city_id");
  const selectedDistrict = watch("district_id");

  useEffect(() => {
    if (isOpen) {
      if (address) {
        reset({
          recipient_name: address.recipient_name,
          phone_number: address.phone_number,
          address_line: address.address_line,
          city_id: address.city_id,
          district_id: address.district_id,
          ward_id: address.ward_id,
        });
      } else {
        reset({
          recipient_name: "",
          phone_number: "",
          address_line: "",
          city_id: "",
          district_id: "",
          ward_id: "",
        });
      }
    }
  }, [isOpen, address, reset]);

  // Reset district and ward when city changes
  useEffect(() => {
    if (isOpen && !address) {
      setValue("district_id", "");
      setValue("ward_id", "");
    }
  }, [selectedCity, setValue, isOpen, address]);

  // Reset ward when district changes
  useEffect(() => {
    if (isOpen && !address) {
      setValue("ward_id", "");
    }
  }, [selectedDistrict, setValue, isOpen, address]);

  if (!isOpen) return null;

  const onSubmit = async (data: FormData) => {
    setSaving(true);
    try {
      await onSave(data);
      onClose();
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 sm:p-0">
      {/* Backdrop */}
      <div 
        className="absolute inset-0 bg-black/80 backdrop-blur-sm transition-opacity" 
        onClick={onClose}
      />
      
      {/* Modal Content */}
      <div className="relative z-10 w-full max-w-lg overflow-hidden rounded-2xl bg-zinc-950 border border-zinc-800 shadow-2xl">
        <div className="flex items-center justify-between border-b border-zinc-800/50 p-6">
          <h2 className="text-xl font-semibold text-white">
            {address ? "Cập nhật địa chỉ" : "Thêm địa chỉ mới"}
          </h2>
          <button 
            onClick={onClose}
            className="rounded-full p-2 text-zinc-400 hover:bg-zinc-800 hover:text-white transition-colors"
          >
            <X className="h-5 w-5" />
          </button>
        </div>

        <form onSubmit={handleSubmit(onSubmit)} className="p-6 space-y-5">
          <div className="grid gap-5 sm:grid-cols-2">
            <div className="space-y-2">
              <Label htmlFor="recipient_name" className="text-zinc-300">Tên người nhận <span className="text-red-500">*</span></Label>
              <Input 
                id="recipient_name" 
                className="bg-black/40 border-zinc-700/50 text-white focus-visible:ring-emerald-500/50"
                {...register("recipient_name", { required: "Bắt buộc nhập" })}
              />
              {errors.recipient_name && <p className="text-sm text-red-500">{errors.recipient_name.message}</p>}
            </div>

            <div className="space-y-2">
              <Label htmlFor="phone_number" className="text-zinc-300">Số điện thoại <span className="text-red-500">*</span></Label>
              <Input 
                id="phone_number" 
                className="bg-black/40 border-zinc-700/50 text-white focus-visible:ring-emerald-500/50"
                {...register("phone_number", { required: "Bắt buộc nhập" })}
              />
              {errors.phone_number && <p className="text-sm text-red-500">{errors.phone_number.message}</p>}
            </div>
          </div>

          <div className="space-y-2">
            <Label className="text-zinc-300">Tỉnh/Thành phố <span className="text-red-500">*</span></Label>
            <Select 
              value={selectedCity} 
              onValueChange={(val) => setValue("city_id", val, { shouldValidate: true })}
            >
              <SelectTrigger className="bg-black/40 border-zinc-700/50 text-white">
                <SelectValue placeholder="Chọn Tỉnh/Thành phố" />
              </SelectTrigger>
              <SelectContent className="bg-zinc-900 border-zinc-800 text-white">
                {PROVINCES.map(p => (
                  <SelectItem key={p.id} value={p.id} className="focus:bg-zinc-800">{p.name}</SelectItem>
                ))}
              </SelectContent>
            </Select>
            <input type="hidden" {...register("city_id", { required: "Bắt buộc chọn" })} />
            {errors.city_id && <p className="text-sm text-red-500">{errors.city_id.message}</p>}
          </div>

          <div className="grid gap-5 sm:grid-cols-2">
            <div className="space-y-2">
              <Label className="text-zinc-300">Quận/Huyện <span className="text-red-500">*</span></Label>
              <Select 
                value={selectedDistrict} 
                onValueChange={(val) => setValue("district_id", val, { shouldValidate: true })}
                disabled={!selectedCity}
              >
                <SelectTrigger className="bg-black/40 border-zinc-700/50 text-white">
                  <SelectValue placeholder="Chọn Quận/Huyện" />
                </SelectTrigger>
                <SelectContent className="bg-zinc-900 border-zinc-800 text-white">
                  {selectedCity && DISTRICTS[selectedCity]?.map(d => (
                    <SelectItem key={d.id} value={d.id} className="focus:bg-zinc-800">{d.name}</SelectItem>
                  ))}
                </SelectContent>
              </Select>
              <input type="hidden" {...register("district_id", { required: "Bắt buộc chọn" })} />
            </div>

            <div className="space-y-2">
              <Label className="text-zinc-300">Phường/Xã <span className="text-red-500">*</span></Label>
              <Select 
                value={watch("ward_id")} 
                onValueChange={(val) => setValue("ward_id", val, { shouldValidate: true })}
                disabled={!selectedDistrict}
              >
                <SelectTrigger className="bg-black/40 border-zinc-700/50 text-white">
                  <SelectValue placeholder="Chọn Phường/Xã" />
                </SelectTrigger>
                <SelectContent className="bg-zinc-900 border-zinc-800 text-white">
                  {selectedDistrict && WARDS[selectedDistrict]?.map(w => (
                    <SelectItem key={w.id} value={w.id} className="focus:bg-zinc-800">{w.name}</SelectItem>
                  ))}
                </SelectContent>
              </Select>
              <input type="hidden" {...register("ward_id", { required: "Bắt buộc chọn" })} />
            </div>
          </div>

          <div className="space-y-2">
            <Label htmlFor="address_line" className="text-zinc-300">Địa chỉ cụ thể <span className="text-red-500">*</span></Label>
            <Input 
              id="address_line" 
              placeholder="Số nhà, tên đường, tòa nhà..."
              className="bg-black/40 border-zinc-700/50 text-white focus-visible:ring-emerald-500/50"
              {...register("address_line", { required: "Bắt buộc nhập" })}
            />
            {errors.address_line && <p className="text-sm text-red-500">{errors.address_line.message}</p>}
          </div>

          <div className="pt-6 flex justify-end gap-3 border-t border-zinc-800/50">
            <Button type="button" variant="ghost" onClick={onClose} className="text-zinc-400 hover:text-white hover:bg-zinc-800">
              Hủy
            </Button>
            <Button 
              type="submit" 
              disabled={saving}
              className="bg-emerald-500 hover:bg-emerald-400 text-black font-semibold shadow-[0_0_15px_rgba(16,185,129,0.2)]"
            >
              {saving ? "Đang lưu..." : "Lưu địa chỉ"}
            </Button>
          </div>
        </form>
      </div>
    </div>
  );
}
