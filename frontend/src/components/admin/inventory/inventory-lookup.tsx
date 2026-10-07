"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { ArrowRight, Boxes, Command, PackageSearch, Plus, ReceiptText, Warehouse } from "lucide-react";

import { FieldBlock } from "@/components/admin/inventory/inventory-shared";
import { PageHeader } from "@/components/admin/page-header";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";

export function InventoryLookup() {
  const router = useRouter();
  const [warehouseId, setWarehouseId] = useState("");
  const [skuId, setSkuId] = useState("");
  const [movementId, setMovementId] = useState("");
  const [commandId, setCommandId] = useState("");

  function go(path: string) {
    router.push(path);
  }

  return (
    <div className="space-y-10">
      <PageHeader
        title="Kho hàng"
        description="Inventory không cung cấp API danh sách. Hãy tra cứu bằng mã chính xác hoặc bắt đầu một lệnh vận hành mới."
        action={
          <Button nativeButton={false} render={<Link href="/admin/inventory/warehouses/new" />}>
            <Plus aria-hidden />Tạo kho
          </Button>
        }
      />

      <section aria-labelledby="inventory-actions" className="space-y-5">
        <div className="space-y-1">
          <h2 id="inventory-actions" className="text-headline-5 font-serif font-normal">Lệnh vận hành</h2>
          <p className="text-caption text-muted-foreground">Mỗi lần gửi là một lệnh có mã idempotency riêng và bằng chứng đi kèm.</p>
        </div>
        <div className="grid gap-4 sm:grid-cols-2">
          <ActionCard icon={ReceiptText} title="Nhập hàng" description="Nạp tồn ban đầu hoặc ghi nhận phiếu nhận từ nhà cung cấp." href="/admin/inventory/receipts/new" />
          <ActionCard icon={Boxes} title="Điều chỉnh tồn" description="Ghi nhận tăng hoặc giảm tồn bán được với lý do được phê duyệt." href="/admin/inventory/adjustments/new" />
        </div>
      </section>

      <section aria-labelledby="inventory-lookups" className="space-y-5">
        <div className="space-y-1">
          <h2 id="inventory-lookups" className="text-headline-5 font-serif font-normal">Tra cứu theo mã</h2>
          <p className="text-caption text-muted-foreground">Mã được giữ nguyên khi mở route và được encode an toàn trong URL.</p>
        </div>
        <div className="grid gap-5 lg:grid-cols-2">
          <LookupCard icon={Warehouse} title="Kho" description="Xem địa chỉ, trạng thái và ưu tiên phân bổ.">
            <FieldBlock id="lookup-warehouse" label="Mã kho">
              <Input id="lookup-warehouse" value={warehouseId} maxLength={128} onChange={(event) => setWarehouseId(event.target.value)} />
            </FieldBlock>
            <Button type="button" variant="outline" disabled={!warehouseId.trim()} onClick={() => go(`/admin/inventory/warehouses/${encodeURIComponent(warehouseId.trim())}`)}>
              Tra kho<ArrowRight aria-hidden />
            </Button>
          </LookupCard>

          <LookupCard icon={PackageSearch} title="Vị trí tồn" description="Xem toàn bộ bucket của một SKU tại một kho.">
            <div className="grid gap-5 sm:grid-cols-2">
              <FieldBlock id="lookup-stock-warehouse" label="Mã kho">
                <Input id="lookup-stock-warehouse" value={warehouseId} maxLength={128} onChange={(event) => setWarehouseId(event.target.value)} />
              </FieldBlock>
              <FieldBlock id="lookup-stock-sku" label="Mã SKU">
                <Input id="lookup-stock-sku" value={skuId} maxLength={128} onChange={(event) => setSkuId(event.target.value)} />
              </FieldBlock>
            </div>
            <Button type="button" variant="outline" disabled={!warehouseId.trim() || !skuId.trim()} onClick={() => go(`/admin/inventory/stock/${encodeURIComponent(warehouseId.trim())}/${encodeURIComponent(skuId.trim())}`)}>
              Tra tồn<ArrowRight aria-hidden />
            </Button>
          </LookupCard>

          <LookupCard icon={Boxes} title="Movement" description="Xem bằng chứng bất biến và tạo reversal khi đủ điều kiện.">
            <FieldBlock id="lookup-movement" label="Mã movement">
              <Input id="lookup-movement" value={movementId} maxLength={128} onChange={(event) => setMovementId(event.target.value)} />
            </FieldBlock>
            <Button type="button" variant="outline" disabled={!movementId.trim()} onClick={() => go(`/admin/inventory/movements/${encodeURIComponent(movementId.trim())}`)}>
              Tra movement<ArrowRight aria-hidden />
            </Button>
          </LookupCard>

          <LookupCard icon={Command} title="Kết quả lệnh" description="Khôi phục kết quả bền vững sau khi mất response hoặc đóng trình duyệt.">
            <FieldBlock id="lookup-command" label="Mã lệnh">
              <Input id="lookup-command" value={commandId} maxLength={128} onChange={(event) => setCommandId(event.target.value)} />
            </FieldBlock>
            <Button type="button" variant="outline" disabled={!commandId.trim()} onClick={() => go(`/admin/inventory/commands/${encodeURIComponent(commandId.trim())}`)}>
              Tra lệnh<ArrowRight aria-hidden />
            </Button>
          </LookupCard>
        </div>
      </section>
    </div>
  );
}

function ActionCard({ icon: Icon, title, description, href }: { icon: typeof Boxes; title: string; description: string; href: string }) {
  return (
    <Link href={href} className="focus-ring flex items-start gap-4 rounded-xl border border-border bg-surface-overlay p-6 transition-colors duration-200 hover:border-border-strong hover:bg-surface-2">
      <span className="grid size-11 shrink-0 place-content-center rounded-lg bg-surface-3 text-foreground-secondary"><Icon className="size-5" aria-hidden /></span>
      <span className="space-y-1"><span className="block text-headline-6 font-medium">{title}</span><span className="block text-caption text-muted-foreground">{description}</span></span>
    </Link>
  );
}

function LookupCard({ icon: Icon, title, description, children }: { icon: typeof Boxes; title: string; description: string; children: React.ReactNode }) {
  return (
    <Card>
      <CardHeader className="grid grid-cols-[auto_1fr] gap-x-3">
        <span className="row-span-2 grid size-10 place-content-center rounded-lg bg-surface-3 text-foreground-secondary"><Icon className="size-5" aria-hidden /></span>
        <CardTitle>{title}</CardTitle><CardDescription>{description}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-5">{children}</CardContent>
    </Card>
  );
}
