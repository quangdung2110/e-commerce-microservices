import { Badge } from "@/components/ui/badge";
import { labelOf, type EnumLabel } from "@/lib/labels/types";

export function StatusBadge<T extends string>({
  value,
  labels,
}: {
  value: T;
  labels: Record<T, EnumLabel>;
}) {
  const label = labelOf(labels, value);
  return <Badge variant={label.tone}>{label.text}</Badge>;
}
