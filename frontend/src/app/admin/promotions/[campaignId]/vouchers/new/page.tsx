import { VoucherCreate } from "@/components/admin/promotions/voucher-form";

export default async function AdminVoucherCreatePage({
  params,
}: {
  params: Promise<{ campaignId: string }>;
}) {
  const { campaignId } = await params;
  return <VoucherCreate campaignId={campaignId} />;
}
