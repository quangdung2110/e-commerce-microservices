"use client";

import { Pencil, Star, Trash2 } from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { formatAddressLine } from "@/lib/constants/address";
import { formatPhone } from "@/lib/format";
import { cn } from "@/lib/utils";
import type { Address } from "@/lib/api/user";

interface AddressCardProps {
  address: Address;
  onEdit: (address: Address) => void;
  onDelete: (address: Address) => void;
  onSetDefault: (address: Address) => void;
  busy?: boolean;
}

export function AddressCard({
  address,
  onEdit,
  onDelete,
  onSetDefault,
  busy = false,
}: AddressCardProps) {
  return (
    <li
      className={cn(
        "rounded-xl border bg-surface-overlay p-5 transition-colors duration-200",
        // Địa chỉ mặc định được nhấn bằng viền clay, không bằng nền màu — nó là
        // một trạng thái, không phải một cảnh báo.
        address.is_default ? "border-clay/40" : "border-border"
      )}
    >
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div className="min-w-0 space-y-1">
          <div className="flex flex-wrap items-center gap-2">
            <p className="text-[15px] font-medium text-foreground">
              {address.recipient_name}
            </p>
            {address.is_default && <Badge variant="clay">Mặc định</Badge>}
          </div>

          <p className="text-[14px] text-muted-foreground tabular-nums">
            {formatPhone(address.recipient_phone)}
          </p>

          <p className="max-w-prose-narrow text-[14px] leading-relaxed text-foreground-secondary">
            {formatAddressLine(address)}
          </p>
        </div>

        <div className="flex shrink-0 items-center gap-1">
          {!address.is_default && (
            <Button
              variant="ghost"
              size="sm"
              onClick={() => onSetDefault(address)}
              disabled={busy}
            >
              <Star className="size-4" aria-hidden />
              Đặt mặc định
            </Button>
          )}

          <Button
            variant="ghost"
            size="icon-sm"
            aria-label={`Sửa địa chỉ của ${address.recipient_name}`}
            onClick={() => onEdit(address)}
            disabled={busy}
          >
            <Pencil className="size-4" aria-hidden />
          </Button>

          <Button
            variant="ghost"
            size="icon-sm"
            aria-label={`Xoá địa chỉ của ${address.recipient_name}`}
            className="text-danger hover:bg-danger-surface hover:text-danger"
            onClick={() => onDelete(address)}
            // Địa chỉ mặc định không xoá được: server trả 409, nên chặn từ đây.
            disabled={busy || address.is_default}
            title={
              address.is_default
                ? "Hãy đặt một địa chỉ khác làm mặc định trước khi xoá địa chỉ này."
                : undefined
            }
          >
            <Trash2 className="size-4" aria-hidden />
          </Button>
        </div>
      </div>
    </li>
  );
}
