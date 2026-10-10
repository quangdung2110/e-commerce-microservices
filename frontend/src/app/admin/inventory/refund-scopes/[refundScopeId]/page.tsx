import { RefundScopeDetail } from "@/components/admin/inventory/refund-scope-detail";
export default async function Page({
  params,
}: {
  params: Promise<{ refundScopeId: string }>;
}) {
  const { refundScopeId } = await params;
  return <RefundScopeDetail scopeId={refundScopeId} />;
}
