import { AttributeDetail } from "@/components/admin/attributes/attribute-detail";

export default async function AdminAttributeDetailPage({
  params,
}: {
  params: Promise<{ attributeId: string }>;
}) {
  const { attributeId } = await params;
  return <AttributeDetail attributeId={attributeId} />;
}
