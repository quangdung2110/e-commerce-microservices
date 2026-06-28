import { Metadata } from "next";
import { ProfileSidebar } from "@/components/profile/ProfileSidebar";

export const metadata: Metadata = {
  title: "Profile | TechNova E-Commerce",
  description: "Manage your account settings and preferences.",
};

export default function ProfileLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <div className="container max-w-6xl py-10">
      <div className="space-y-0.5 mb-8">
        <h2 className="text-3xl font-bold tracking-tight text-white">Cài đặt tài khoản</h2>
        <p className="text-zinc-400 text-sm">
          Quản lý thông tin cá nhân, địa chỉ giao hàng và tùy chọn thông báo của bạn.
        </p>
      </div>
      
      <div className="flex flex-col space-y-8 lg:flex-row lg:space-x-12 lg:space-y-0">
        <aside className="lg:w-1/4">
          <div className="sticky top-20 rounded-xl bg-black/40 backdrop-blur-md border border-white/10 p-4">
            <ProfileSidebar />
          </div>
        </aside>
        <div className="flex-1 lg:max-w-3xl">{children}</div>
      </div>
    </div>
  );
}
