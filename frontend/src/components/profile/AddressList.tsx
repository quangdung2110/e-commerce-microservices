"use client";

import { useState } from "react";
import { LoaderCircle, MapPin, Plus } from "lucide-react";

import { AddressCard } from "@/components/profile/AddressCard";
import { AddressForm } from "@/components/profile/AddressForm";
import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { useToast } from "@/components/ui/toast";
import {
  useAddresses,
  useCreateAddress,
  useDeleteAddress,
  useReplaceAddress,
  useSetDefaultAddress,
} from "@/hooks/use-user";
import { formatAddressLine } from "@/lib/constants/address";
import { problemMessage } from "@/lib/api/problem";
import type { Address, AddressWrite } from "@/lib/api/user";

type Editing =
  | { kind: "none" }
  | { kind: "create" }
  | { kind: "edit"; address: Address };

export function AddressList() {
  const { data: addresses, isPending, isError, refetch } = useAddresses();
  const createAddress = useCreateAddress();
  const replaceAddress = useReplaceAddress();
  const deleteAddress = useDeleteAddress();
  const setDefaultAddress = useSetDefaultAddress();
  const { addToast } = useToast();

  const [editing, setEditing] = useState<Editing>({ kind: "none" });
  const [pendingDelete, setPendingDelete] = useState<Address | null>(null);

  async function handleCreate(input: AddressWrite) {
    await createAddress.mutateAsync(input);
    setEditing({ kind: "none" });
    addToast({ type: "success", title: "Đã thêm địa chỉ" });
  }

  async function handleReplace(id: string, input: AddressWrite) {
    await replaceAddress.mutateAsync({ id, input });
    setEditing({ kind: "none" });
    addToast({ type: "success", title: "Đã cập nhật địa chỉ" });
  }

  async function handleDelete(address: Address) {
    try {
      await deleteAddress.mutateAsync(address.id);
      addToast({ type: "success", title: "Đã xoá địa chỉ" });
    } catch (error) {
      addToast({
        type: "error",
        title: "Không xoá được địa chỉ",
        message: problemMessage(error),
      });
    } finally {
      setPendingDelete(null);
    }
  }

  async function handleSetDefault(address: Address) {
    try {
      await setDefaultAddress.mutateAsync(address.id);
      addToast({ type: "success", title: "Đã đổi địa chỉ mặc định" });
    } catch (error) {
      addToast({
        type: "error",
        title: "Không đổi được địa chỉ mặc định",
        message: problemMessage(error),
      });
    }
  }

  if (isError) {
    return (
      <Card>
        <CardContent className="space-y-4">
          <Alert variant="danger">
            Không tải được sổ địa chỉ. Vui lòng thử lại.
          </Alert>
          <Button variant="outline" onClick={() => void refetch()}>
            Thử lại
          </Button>
        </CardContent>
      </Card>
    );
  }

  // Kiểm cả `!addresses`: sau khi destructure, TypeScript không còn liên kết
  // được `isPending` với việc `data` đã có hay chưa.
  if (isPending || !addresses) return <AddressListSkeleton />;

  const busy =
    deleteAddress.isPending || setDefaultAddress.isPending || replaceAddress.isPending;

  return (
    <div className="space-y-6">
      {pendingDelete && (
        <Alert variant="danger" title="Xoá địa chỉ này?">
          <p className="mb-3">
            {pendingDelete.recipient_name} — {formatAddressLine(pendingDelete)}. Thao
            tác này không hoàn tác được.
          </p>
          <div className="flex gap-2">
            <Button
              variant="destructive"
              size="sm"
              onClick={() => void handleDelete(pendingDelete)}
              disabled={deleteAddress.isPending}
            >
              {deleteAddress.isPending ? (
                <>
                  <LoaderCircle className="animate-spin" aria-hidden />
                  Đang xoá
                </>
              ) : (
                "Xoá địa chỉ"
              )}
            </Button>
            <Button
              variant="ghost"
              size="sm"
              onClick={() => setPendingDelete(null)}
              disabled={deleteAddress.isPending}
            >
              Giữ lại
            </Button>
          </div>
        </Alert>
      )}

      {editing.kind === "create" && (
        <AddressForm
          onSubmit={handleCreate}
          onCancel={() => setEditing({ kind: "none" })}
        />
      )}

      {addresses.length === 0 && editing.kind === "none" ? (
        <div className="flex flex-col items-center gap-3 rounded-xl border border-dashed border-border bg-surface-2 px-6 py-12 text-center">
          <MapPin className="size-8 text-muted-foreground" aria-hidden />
          <p className="text-[15px] font-medium">Chưa có địa chỉ nào</p>
          <p className="max-w-prose-narrow text-caption text-muted-foreground">
            Thêm địa chỉ giao hàng để thanh toán nhanh hơn ở những lần mua sau.
          </p>
          <Button variant="outline" size="sm" onClick={() => setEditing({ kind: "create" })}>
            <Plus className="size-4" aria-hidden />
            Thêm địa chỉ
          </Button>
        </div>
      ) : (
        <ul className="space-y-4">
          {addresses.map((address) =>
            editing.kind === "edit" && editing.address.id === address.id ? (
              <li key={address.id}>
                <AddressForm
                  address={address}
                  onSubmit={(input) => handleReplace(address.id, input)}
                  onCancel={() => setEditing({ kind: "none" })}
                />
              </li>
            ) : (
              <AddressCard
                key={address.id}
                address={address}
                busy={busy}
                isOnlyAddress={addresses.length === 1}
                onEdit={(item) => setEditing({ kind: "edit", address: item })}
                onDelete={setPendingDelete}
                onSetDefault={(item) => void handleSetDefault(item)}
              />
            )
          )}
        </ul>
      )}

      {editing.kind === "none" && addresses.length > 0 && (
        <Button variant="outline" onClick={() => setEditing({ kind: "create" })}>
          <Plus className="size-4" aria-hidden />
          Thêm địa chỉ
        </Button>
      )}
    </div>
  );
}

function AddressListSkeleton() {
  return (
    <div className="space-y-4" aria-busy="true">
      {[0, 1].map((index) => (
        <div key={index} className="rounded-xl border border-border bg-surface-overlay p-5">
          <div className="space-y-2">
            <Skeleton className="h-5 w-40" />
            <Skeleton className="h-4 w-32" />
            <Skeleton className="h-4 w-full max-w-md" />
          </div>
        </div>
      ))}
    </div>
  );
}
