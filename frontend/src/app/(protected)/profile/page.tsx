import { ProfileForm } from "@/components/profile/ProfileForm";

export default function ProfilePage() {
  return (
    <div className="space-y-6">
      <div>
        <h3 className="text-2xl font-semibold text-white tracking-tight">Hồ sơ cá nhân</h3>
        <p className="text-sm text-zinc-400 mt-1">
          Đây là cách người khác sẽ nhìn thấy bạn trên trang web.
        </p>
      </div>
      
      <div className="rounded-xl border border-zinc-800/50 bg-black/20 p-6 backdrop-blur-xl">
        <ProfileForm />
      </div>
    </div>
  );
}
