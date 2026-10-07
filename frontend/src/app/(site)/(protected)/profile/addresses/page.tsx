import type { Metadata } from "next";

import { AddressList } from "@/components/profile/AddressList";

export const metadata: Metadata = {
  title: "Sổ địa chỉ",
  description: "Quản lý địa chỉ giao hàng của bạn.",
};

export default function AddressesPage() {
  return (
    <section className="space-y-6">
      <header className="space-y-2">
        <h2 className="text-headline-5">Sổ địa chỉ</h2>
        <p className="max-w-prose-narrow text-[15px] text-muted-foreground">
          Địa chỉ mặc định được điền sẵn khi bạn thanh toán. Đơn đã đặt giữ bản
          sao địa chỉ tại thời điểm đặt, nên sửa ở đây không làm thay đổi đơn cũ.
        </p>
      </header>

      <AddressList />
    </section>
  );
}
