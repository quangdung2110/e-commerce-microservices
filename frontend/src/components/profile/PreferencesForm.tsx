"use client";

import { useEffect, useState } from "react";
import { UserPreferences, userService } from "@/services/user/api";
import { Switch } from "@/components/ui/switch";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Label } from "@/components/ui/label";
import { useToast } from "@/components/ui/toast";
import { Skeleton } from "@/components/ui/skeleton";
import { Bell, Mail, Smartphone, Globe, Coins, ShieldAlert } from "lucide-react";
import { Button } from "@/components/ui/button";

export function PreferencesForm() {
  const { toast } = useToast();
  const [loading, setLoading] = useState(true);
  const [preferences, setPreferences] = useState<UserPreferences | null>(null);

  useEffect(() => {
    const loadPreferences = async () => {
      try {
        const data = await userService.getPreferences();
        setPreferences(data);
      } catch (error) {
        toast({
          title: "Lỗi",
          description: "Không thể tải cài đặt của bạn.",
          variant: "destructive",
        });
      } finally {
        setLoading(false);
      }
    };
    loadPreferences();
  }, [toast]);

  const updatePref = async (key: keyof UserPreferences, value: any) => {
    if (!preferences) return;
    
    // Optimistic UI Update
    const prev = { ...preferences };
    setPreferences({ ...preferences, [key]: value });

    try {
      await userService.updatePreferences({ [key]: value });
      toast({
        title: "Đã lưu",
        description: "Cài đặt đã được tự động lưu.",
      });
    } catch (error) {
      // Revert on failure
      setPreferences(prev);
      toast({
        title: "Lỗi",
        description: "Không thể lưu cài đặt. Vui lòng thử lại.",
        variant: "destructive",
      });
    }
  };

  const handleAccountDeletion = () => {
    toast({
      title: "Yêu cầu xóa tài khoản",
      description: "Tính năng này đang được phát triển (GDPR Compliance).",
      variant: "destructive",
    });
  };

  if (loading || !preferences) {
    return (
      <div className="space-y-6">
        <Skeleton className="h-24 w-full bg-zinc-800/50 rounded-xl animate-pulse" />
        <Skeleton className="h-24 w-full bg-zinc-800/50 rounded-xl animate-pulse" />
        <Skeleton className="h-24 w-full bg-zinc-800/50 rounded-xl animate-pulse" />
      </div>
    );
  }

  return (
    <div className="space-y-8">
      {/* Localization Settings */}
      <div className="space-y-4">
        <h4 className="text-sm font-medium text-zinc-400 uppercase tracking-wider">Ngôn ngữ & Khu vực</h4>
        <div className="grid gap-4 md:grid-cols-2">
          <div className="flex items-start gap-4 rounded-xl border border-zinc-800/50 bg-black/40 p-4">
            <Globe className="h-5 w-5 text-zinc-400 mt-0.5" />
            <div className="space-y-2 flex-1">
              <Label className="text-zinc-200">Ngôn ngữ hiển thị</Label>
              <Select 
                value={preferences.language} 
                onValueChange={(val) => updatePref("language", val)}
              >
                <SelectTrigger className="bg-zinc-900 border-zinc-700 text-white h-9">
                  <SelectValue placeholder="Chọn ngôn ngữ" />
                </SelectTrigger>
                <SelectContent className="bg-zinc-900 border-zinc-800 text-white">
                  <SelectItem value="vi" className="focus:bg-zinc-800">Tiếng Việt</SelectItem>
                  <SelectItem value="en" className="focus:bg-zinc-800">English</SelectItem>
                </SelectContent>
              </Select>
            </div>
          </div>

          <div className="flex items-start gap-4 rounded-xl border border-zinc-800/50 bg-black/40 p-4">
            <Coins className="h-5 w-5 text-zinc-400 mt-0.5" />
            <div className="space-y-2 flex-1">
              <Label className="text-zinc-200">Tiền tệ ưu tiên</Label>
              <Select 
                value={preferences.currency} 
                onValueChange={(val) => updatePref("currency", val)}
              >
                <SelectTrigger className="bg-zinc-900 border-zinc-700 text-white h-9">
                  <SelectValue placeholder="Chọn tiền tệ" />
                </SelectTrigger>
                <SelectContent className="bg-zinc-900 border-zinc-800 text-white">
                  <SelectItem value="VND" className="focus:bg-zinc-800">VND (₫)</SelectItem>
                  <SelectItem value="USD" className="focus:bg-zinc-800">USD ($)</SelectItem>
                </SelectContent>
              </Select>
            </div>
          </div>
        </div>
      </div>

      {/* Notification Settings */}
      <div className="space-y-4">
        <h4 className="text-sm font-medium text-zinc-400 uppercase tracking-wider">Cài đặt thông báo</h4>
        <div className="space-y-3">
          <div className="flex items-center justify-between rounded-xl border border-zinc-800/50 bg-black/40 p-4 transition-colors hover:border-zinc-700">
            <div className="flex items-center gap-4">
              <div className="flex h-10 w-10 items-center justify-center rounded-full bg-zinc-900 border border-zinc-800">
                <Mail className="h-5 w-5 text-zinc-300" />
              </div>
              <div>
                <Label className="text-base text-zinc-200 cursor-pointer">Thông báo qua Email</Label>
                <p className="text-sm text-zinc-500">Nhận cập nhật đơn hàng và cảnh báo bảo mật qua email.</p>
              </div>
            </div>
            <Switch 
              checked={preferences.email_enabled}
              onCheckedChange={(checked) => updatePref("email_enabled", checked)}
              className="data-[state=checked]:bg-emerald-500"
            />
          </div>

          <div className="flex items-center justify-between rounded-xl border border-zinc-800/50 bg-black/40 p-4 transition-colors hover:border-zinc-700">
            <div className="flex items-center gap-4">
              <div className="flex h-10 w-10 items-center justify-center rounded-full bg-zinc-900 border border-zinc-800">
                <Smartphone className="h-5 w-5 text-zinc-300" />
              </div>
              <div>
                <Label className="text-base text-zinc-200 cursor-pointer">Thông báo In-App (Push)</Label>
                <p className="text-sm text-zinc-500">Thông báo trực tiếp trên ứng dụng / trình duyệt.</p>
              </div>
            </div>
            <Switch 
              checked={preferences.in_app_enabled}
              onCheckedChange={(checked) => updatePref("in_app_enabled", checked)}
              className="data-[state=checked]:bg-emerald-500"
            />
          </div>

          <div className="flex items-center justify-between rounded-xl border border-zinc-800/50 bg-black/40 p-4 transition-colors hover:border-zinc-700">
            <div className="flex items-center gap-4">
              <div className="flex h-10 w-10 items-center justify-center rounded-full bg-zinc-900 border border-zinc-800">
                <Bell className="h-5 w-5 text-zinc-300" />
              </div>
              <div>
                <Label className="text-base text-zinc-200 cursor-pointer">Tin khuyến mãi & Marketing</Label>
                <p className="text-sm text-zinc-500">Nhận thông tin về các ưu đãi, mã giảm giá mới nhất.</p>
              </div>
            </div>
            <Switch 
              checked={preferences.marketing_enabled}
              onCheckedChange={(checked) => updatePref("marketing_enabled", checked)}
              className="data-[state=checked]:bg-emerald-500"
            />
          </div>
        </div>
      </div>

      {/* Danger Zone */}
      <div className="mt-12 space-y-4">
        <h4 className="text-sm font-medium text-red-500/80 uppercase tracking-wider">Danger Zone</h4>
        <div className="rounded-xl border border-red-900/30 bg-red-950/10 p-5">
          <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
            <div className="flex items-start gap-4">
              <ShieldAlert className="h-6 w-6 text-red-500 mt-1" />
              <div>
                <h5 className="text-base font-medium text-zinc-200">Xóa tài khoản</h5>
                <p className="text-sm text-zinc-500 mt-1 max-w-md">
                  Xóa vĩnh viễn dữ liệu tài khoản và hồ sơ của bạn khỏi hệ thống. Việc này không thể hoàn tác sau 90 ngày.
                </p>
              </div>
            </div>
            <Button 
              variant="destructive" 
              onClick={handleAccountDeletion}
              className="bg-red-500/10 text-red-500 border border-red-500/20 hover:bg-red-500 hover:text-white transition-all shrink-0"
            >
              Yêu cầu xóa
            </Button>
          </div>
        </div>
      </div>
    </div>
  );
}
