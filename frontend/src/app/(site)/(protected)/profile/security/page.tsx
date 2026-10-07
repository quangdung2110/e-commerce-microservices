import type { Metadata } from "next";

import { ChangePasswordCard } from "@/components/profile/ChangePasswordCard";
import { DeleteAccountCard } from "@/components/profile/DeleteAccountCard";
import { EmailChangeCard } from "@/components/profile/EmailChangeCard";
import { SessionsCard } from "@/components/profile/SessionsCard";

export const metadata: Metadata = {
  title: "Bảo mật",
  description: "Mật khẩu, email đăng nhập và các thiết bị đang đăng nhập.",
};

export default function SecurityPage() {
  return (
    <>
      <SessionsCard />
      <ChangePasswordCard />
      <EmailChangeCard />
      <DeleteAccountCard />
    </>
  );
}
