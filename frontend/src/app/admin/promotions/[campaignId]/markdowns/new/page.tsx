import { MarkdownCreate } from "@/components/admin/promotions/markdown-form";

export default async function AdminMarkdownCreatePage({
  params,
}: {
  params: Promise<{ campaignId: string }>;
}) {
  const { campaignId } = await params;
  return <MarkdownCreate campaignId={campaignId} />;
}
