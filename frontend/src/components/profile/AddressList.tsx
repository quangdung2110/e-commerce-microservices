"use client";

import { useState } from "react";
import { UserAddress } from "@/services/user/api";
import { 
  useAddresses, 
  useCreateAddress, 
  useUpdateAddress, 
  useDeleteAddress, 
  useSetDefaultAddress 
} from "@/hooks/use-user";
import { AddressCard } from "@/components/profile/AddressCard";
import { AddressDialog } from "@/components/profile/AddressDialog";
import { Button } from "@/components/ui/button";
import { useToast } from "@/components/ui/toast";
import { Plus, MapPin } from "lucide-react";
import { Skeleton } from "@/components/ui/skeleton";

export function AddressList() {
  const { toast } = useToast();
  const { data: rawAddresses, isLoading, isError } = useAddresses();
  
  const createAddressMutation = useCreateAddress();
  const updateAddressMutation = useUpdateAddress();
  const deleteAddressMutation = useDeleteAddress();
  const setDefaultMutation = useSetDefaultAddress();
  
  const [isDialogOpen, setIsDialogOpen] = useState(false);
  const [editingAddress, setEditingAddress] = useState<UserAddress | null>(null);

  const addresses = rawAddresses 
    ? [...rawAddresses].sort((a, b) => (b.is_default ? 1 : 0) - (a.is_default ? 1 : 0))
    : [];

  const handleOpenCreate = () => {
    setEditingAddress(null);
    setIsDialogOpen(true);
  };

  const handleOpenEdit = (address: UserAddress) => {
    setEditingAddress(address);
    setIsDialogOpen(true);
  };

  const handleDelete = (id: string) => {
    deleteAddressMutation.mutate(id, {
      onSuccess: () => {
        toast({ title: "Thành công", description: "Đã xóa địa chỉ." });
      },
      onError: (error: any) => {
        toast({
          title: "Không thể xóa",
          description: error.message || "Có lỗi xảy ra.",
          variant: "destructive",
        });
      }
    });
  };

  const handleSetDefault = (id: string) => {
    setDefaultMutation.mutate(id, {
      onSuccess: () => {
        toast({ title: "Thành công", description: "Đã cập nhật địa chỉ mặc định." });
      },
      onError: () => {
        toast({
          title: "Lỗi cập nhật",
          description: "Không thể thiết lập mặc định.",
          variant: "destructive",
        });
      }
    });
  };

  const handleSave = async (data: Omit<UserAddress, "id" | "user_id" | "is_default">) => {
    if (editingAddress) {
      await updateAddressMutation.mutateAsync({ id: editingAddress.id, data });
      toast({ title: "Thành công", description: "Đã cập nhật địa chỉ." });
    } else {
      await createAddressMutation.mutateAsync(data);
      toast({ title: "Thành công", description: "Đã thêm địa chỉ mới." });
    }
    setIsDialogOpen(false);
  };

  if (isLoading) {
    return (
      <div className="space-y-4">
        <Skeleton className="h-32 w-full bg-zinc-800/50 rounded-xl animate-pulse" />
        <Skeleton className="h-32 w-full bg-zinc-800/50 rounded-xl animate-pulse" />
      </div>
    );
  }

  if (isError) {
    return (
      <div className="text-red-500 bg-red-500/10 p-4 rounded-lg border border-red-500/20">
        Không thể tải danh sách địa chỉ.
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div className="space-y-1">
          <h3 className="text-xl font-semibold text-white">Danh sách địa chỉ</h3>
          <p className="text-sm text-zinc-400">
            Quản lý các địa chỉ giao hàng của bạn ({addresses.length} địa chỉ)
          </p>
        </div>
        <Button 
          onClick={handleOpenCreate}
          className="bg-emerald-500 hover:bg-emerald-400 text-black font-semibold shrink-0"
        >
          <Plus className="mr-2 h-4 w-4" /> Thêm địa chỉ mới
        </Button>
      </div>

      {addresses.length === 0 ? (
        <div className="rounded-xl border border-dashed border-zinc-700/50 bg-black/20 p-12 text-center">
          <MapPin className="mx-auto h-12 w-12 text-zinc-600 mb-4" />
          <h4 className="text-lg font-medium text-white mb-2">Chưa có địa chỉ nào</h4>
          <p className="text-zinc-400 text-sm mb-6 max-w-sm mx-auto">
            Bạn chưa thêm địa chỉ giao hàng nào. Thêm địa chỉ ngay để thanh toán nhanh hơn trong tương lai.
          </p>
          <Button variant="outline" onClick={handleOpenCreate} className="border-zinc-700 text-zinc-300">
            Thêm địa chỉ đầu tiên
          </Button>
        </div>
      ) : (
        <div className="grid gap-4">
          {addresses.map((address) => (
            <AddressCard 
              key={address.id} 
              address={address} 
              onEdit={handleOpenEdit}
              onDelete={handleDelete}
              onSetDefault={handleSetDefault}
            />
          ))}
        </div>
      )}

      <AddressDialog 
        isOpen={isDialogOpen}
        onClose={() => setIsDialogOpen(false)}
        onSave={handleSave}
        address={editingAddress}
      />
    </div>
  );
}
