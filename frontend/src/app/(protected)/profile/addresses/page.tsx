import { AddressList } from "@/components/profile/AddressList";

export default function AddressesPage() {
  return (
    <div className="space-y-6">
      <div>
        <h3 className="text-2xl font-semibold text-white tracking-tight">Sổ địa chỉ</h3>
        <p className="text-sm text-zinc-400 mt-1">
          Quản lý các địa chỉ nhận hàng của bạn để thanh toán nhanh chóng hơn.
        </p>
      </div>
      
      <div className="rounded-xl border border-zinc-800/50 bg-black/20 p-6 backdrop-blur-xl">
        <AddressList />
      </div>
    </div>
  );
}
