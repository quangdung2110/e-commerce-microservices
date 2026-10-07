import type { Metadata } from "next";

import { ProfileSidebar } from "@/components/profile/ProfileSidebar";

export const metadata: Metadata = {
  title: "Tài khoản",
  description: "Quản lý hồ sơ, địa chỉ giao hàng và tuỳ chọn thông báo.",
};

export default function ProfileLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <div className="mx-auto w-full max-w-small px-6 py-10 md:px-8 md:py-14">
      <header className="mb-10 space-y-2">
        <h1 className="text-headline-2">Tài khoản</h1>
        <p className="max-w-prose-narrow text-[15px] text-muted-foreground">
          Quản lý thông tin cá nhân, sổ địa chỉ giao hàng và cách chúng tôi liên
          lạc với bạn.
        </p>
      </header>

      <div className="md:grid md:grid-cols-[220px_1fr] md:gap-12">
        <aside className="mb-8 md:mb-0">
          <div className="md:sticky md:top-24">
            <ProfileSidebar />
          </div>
        </aside>

        <div className="min-w-0 space-y-8">{children}</div>
      </div>
    </div>
  );
}
