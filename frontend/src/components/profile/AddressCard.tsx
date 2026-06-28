"use client";

import { UserAddress } from "@/services/user/api";
import { PROVINCES, DISTRICTS, WARDS } from "@/lib/mock-locations";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { MapPin, Phone, User, Edit2, Trash2, CheckCircle2 } from "lucide-react";
import { cn } from "@/lib/utils";

interface AddressCardProps {
  address: UserAddress;
  onEdit: (address: UserAddress) => void;
  onDelete: (id: string) => void;
  onSetDefault: (id: string) => void;
}

export function AddressCard({ address, onEdit, onDelete, onSetDefault }: AddressCardProps) {
  // Translate IDs to Names
  const provinceName = PROVINCES.find((p) => p.id === address.city_id)?.name || address.city_id;
  const districtName = DISTRICTS[address.city_id]?.find((d) => d.id === address.district_id)?.name || address.district_id;
  const wardName = WARDS[address.district_id]?.find((w) => w.id === address.ward_id)?.name || address.ward_id;

  const fullAddress = `${address.address_line}, ${wardName}, ${districtName}, ${provinceName}`;

  return (
    <div 
      className={cn(
        "group relative overflow-hidden rounded-xl border p-5 transition-all duration-300",
        address.is_default
          ? "border-emerald-500/50 bg-emerald-500/5 shadow-[0_0_15px_rgba(16,185,129,0.1)]"
          : "border-zinc-800/50 bg-black/20 hover:border-zinc-700 hover:bg-zinc-900/50"
      )}
    >
      {/* Decorative Gradient Background */}
      {address.is_default && (
        <div className="absolute -right-20 -top-20 h-40 w-40 rounded-full bg-emerald-500/10 blur-3xl" />
      )}

      <div className="flex flex-col sm:flex-row sm:items-start sm:justify-between gap-4 relative z-10">
        <div className="space-y-3">
          <div className="flex items-center gap-3">
            <h4 className="font-semibold text-zinc-100 flex items-center gap-2">
              <User className="h-4 w-4 text-zinc-400" />
              {address.recipient_name}
            </h4>
            {address.is_default && (
              <Badge className="bg-emerald-500/20 text-emerald-400 border-emerald-500/30 hover:bg-emerald-500/30 flex items-center gap-1">
                <CheckCircle2 className="h-3 w-3" />
                Mặc định
              </Badge>
            )}
          </div>

          <div className="space-y-1.5">
            <p className="text-sm text-zinc-400 flex items-start gap-2">
              <Phone className="h-4 w-4 text-zinc-500 shrink-0 mt-0.5" />
              {address.phone_number}
            </p>
            <p className="text-sm text-zinc-400 flex items-start gap-2 leading-relaxed">
              <MapPin className="h-4 w-4 text-zinc-500 shrink-0 mt-0.5" />
              {fullAddress}
            </p>
          </div>
        </div>

        <div className="flex items-center sm:flex-col justify-end gap-2 shrink-0">
          {!address.is_default && (
            <Button 
              variant="outline" 
              size="sm" 
              onClick={() => onSetDefault(address.id)}
              className="text-xs bg-transparent border-zinc-700 text-zinc-300 hover:text-white hover:border-emerald-500/50 hover:bg-emerald-500/10 transition-colors w-full sm:w-auto"
            >
              Thiết lập mặc định
            </Button>
          )}
          
          <div className="flex items-center gap-2 w-full sm:w-auto mt-auto sm:mt-2">
            <Button 
              variant="ghost" 
              size="icon" 
              onClick={() => onEdit(address)}
              className="h-8 w-8 text-zinc-400 hover:text-white hover:bg-zinc-800"
              title="Chỉnh sửa"
            >
              <Edit2 className="h-4 w-4" />
            </Button>
            <Button 
              variant="ghost" 
              size="icon" 
              onClick={() => onDelete(address.id)}
              disabled={address.is_default}
              className={cn(
                "h-8 w-8",
                address.is_default 
                  ? "opacity-50 cursor-not-allowed text-zinc-600" 
                  : "text-red-400/70 hover:text-red-400 hover:bg-red-400/10"
              )}
              title={address.is_default ? "Không thể xóa địa chỉ mặc định" : "Xóa địa chỉ"}
            >
              <Trash2 className="h-4 w-4" />
            </Button>
          </div>
        </div>
      </div>
    </div>
  );
}
