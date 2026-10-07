import type { Metadata } from "next";

import { AccountCard } from "@/components/profile/AccountCard";
import { ProfileForm } from "@/components/profile/ProfileForm";

export const metadata: Metadata = {
  title: "Hồ sơ cá nhân",
  description: "Xem và cập nhật thông tin cá nhân của bạn.",
};

export default function ProfilePage() {
  return (
    <>
      <ProfileForm />
      <AccountCard />
    </>
  );
}
