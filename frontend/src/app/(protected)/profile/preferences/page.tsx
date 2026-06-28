import { PreferencesForm } from "@/components/profile/PreferencesForm";

export default function PreferencesPage() {
  return (
    <div className="space-y-6">
      <div>
        <h3 className="text-2xl font-semibold text-white tracking-tight">Cài đặt & Thông báo</h3>
        <p className="text-sm text-zinc-400 mt-1">
          Quản lý các tùy chọn hiển thị và phương thức nhận thông báo từ hệ thống.
        </p>
      </div>
      
      <div className="rounded-xl border border-zinc-800/50 bg-black/20 p-6 backdrop-blur-xl">
        <PreferencesForm />
      </div>
    </div>
  );
}
