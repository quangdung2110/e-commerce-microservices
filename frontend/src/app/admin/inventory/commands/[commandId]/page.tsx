import { CommandDetail } from "@/components/admin/inventory/command-detail";

export default async function AdminInventoryCommandPage({ params }: { params: Promise<{ commandId: string }> }) {
  const { commandId } = await params;
  return <CommandDetail commandId={commandId} />;
}
