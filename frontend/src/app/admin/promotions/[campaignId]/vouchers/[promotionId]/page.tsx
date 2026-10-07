import { VoucherDetail } from "@/components/admin/promotions/voucher-form";

export default async function AdminVoucherPage({
  params,
}: {
  params: Promise<{ promotionId: string }>;
}) {
  const { promotionId } = await params;
  return <VoucherDetail promotionId={promotionId} />;
}
