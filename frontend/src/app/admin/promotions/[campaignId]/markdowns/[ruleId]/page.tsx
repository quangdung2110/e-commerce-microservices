import { MarkdownDetail } from "@/components/admin/promotions/markdown-form";

export default async function AdminMarkdownPage({
  params,
}: {
  params: Promise<{ ruleId: string }>;
}) {
  const { ruleId } = await params;
  return <MarkdownDetail ruleId={ruleId} />;
}
