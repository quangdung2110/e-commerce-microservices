import { VoucherWalletContent } from "@/components/vouchers/voucher-wallet-content";
import type { WalletFilter } from "@/lib/api/promotion";

export const metadata = { title: "Ví voucher - TechNova", description: "Voucher đã lưu, đã dùng và hết hạn của bạn" };

export default async function VoucherWalletPage({ searchParams }: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const params = await searchParams;
  const value = params.status;
  const status = typeof value === "string" && ["SAVED", "USED", "EXPIRED"].includes(value) ? value as WalletFilter : undefined;
  const invalid = value !== undefined && status === undefined;
  return <VoucherWalletContent status={status} invalidFilter={invalid} />;
}
